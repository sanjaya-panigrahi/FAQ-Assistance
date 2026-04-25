import re
import time
import json
from functools import lru_cache

from collections.abc import Generator

import chromadb
import redis

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI

from ..analytics_client import post_analytics_event
from ..cached_embeddings import CachedOpenAIEmbeddings
from ..config import settings
from ..schemas import RetrievedChunk, RetrievalQueryRequest, RetrievalQueryResponse
from ..streaming import stream_llm_response, sse_event


class RetrievalPipeline:
    def __init__(self) -> None:
        self._redis = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            decode_responses=True,
        )
        self._chroma_client = chromadb.HttpClient(
            host=settings.chroma_host, port=settings.chroma_port,
        )
        self._embeddings = CachedOpenAIEmbeddings(model=settings.openai_embedding_model)
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._warmup()

    def _warmup(self) -> None:
        """Pre-establish OpenAI API connections to avoid cold-start latency."""
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception:
            return {"status": "DEGRADED", "backend": "chromadb"}

    def rebuild_index(self) -> int:
        return 0  # Index managed by faq-ingestion service.

    def query(self, request: RetrievalQueryRequest) -> RetrievalQueryResponse:
        cache_key = f"retrieval:{request.tenantId}:{request.question}:{request.topK}:{request.similarityThreshold}:{request.queryContext}"
        try:
            cached = self._redis.get(cache_key)
            if cached:
                payload = json.loads(cached)
                return RetrievalQueryResponse.model_validate(payload)
        except Exception:
            pass

        retrieval_start = time.perf_counter()
        transformed_query = self._transform_query(request.question, request.queryContext)
        ranked_chunks = self._retrieve_and_rerank(
            transformed_query=transformed_query,
            tenant_id=request.tenantId,
            top_k=request.topK,
            similarity_threshold=request.similarityThreshold,
        )
        retrieval_latency_ms = int((time.perf_counter() - retrieval_start) * 1000)

        generation_start = time.perf_counter()
        answer, grounded = self._grounded_generate(request.question, ranked_chunks, request.tenantId)
        generation_latency_ms = int((time.perf_counter() - generation_start) * 1000)

        response_chunks = [
            RetrievedChunk(
                rank=index + 1,
                source=item["source"],
                chunkNumber=item["chunk_number"],
                score=round(item["rerank_score"], 4),
                excerpt=item["content"],
            )
            for index, item in enumerate(ranked_chunks)
        ]

        response = RetrievalQueryResponse(
            tenantId=request.tenantId,
            question=request.question,
            transformedQuery=transformed_query,
            strategy="query-transform+hybrid-retrieval+rerank+grounded-generation",
            answer=answer,
            chunksUsed=len(response_chunks),
            grounded=grounded,
            retrievalLatencyMs=retrieval_latency_ms,
            generationLatencyMs=generation_latency_ms,
            chunks=response_chunks,
        )

        post_analytics_event(
            question=request.question, response_text=answer,
            customer_id=request.tenantId or "default", rag_pattern="retrieval",
            framework="langchain", strategy=response.strategy,
            latency_ms=retrieval_latency_ms + generation_latency_ms,
            context_docs="\n\n".join(c["content"] for c in ranked_chunks),
        )

        try:
            self._redis.setex(
                cache_key,
                settings.redis_ttl_seconds,
                response.model_dump_json(),
            )
        except Exception:
            pass

        return response

    def _transform_query(self, question: str, query_context: str | None) -> str:
        query = question.strip()
        if query_context:
            return f"{query} {query_context.strip()}"
        return query

    def _retrieve_and_rerank(
        self,
        transformed_query: str,
        tenant_id: str,
        top_k: int,
        similarity_threshold: float,
    ) -> list[dict]:
        collection_name = f"{settings.chroma_collection_prefix}{tenant_id or 'default'}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection_name,
            embedding_function=self._embeddings,
        )

        candidate_map: dict[tuple[str, int | None, str], dict] = {}
        vector_hits = vector_store.similarity_search_with_relevance_scores(
            transformed_query,
            k=max(top_k * 2, 6),
        )
        for doc, score in vector_hits:
            source = str(doc.metadata.get("source", "unknown"))
            chunk_number = doc.metadata.get("chunk_number")
            key = (source, chunk_number, doc.page_content[:120])
            candidate = candidate_map.setdefault(
                key,
                {
                    "content": doc.page_content,
                    "source": source,
                    "chunk_number": chunk_number,
                    "vector_score": 0.0,
                    "lexical_score": 0.0,
                },
            )
            candidate["vector_score"] = max(candidate["vector_score"], float(score))

        # Score lexical overlap only on vector-retrieved candidates (not the full collection)
        lexical_query_tokens = self._tokenize(transformed_query)
        for candidate in candidate_map.values():
            candidate["lexical_score"] = max(
                candidate["lexical_score"],
                self._lexical_score(lexical_query_tokens, self._tokenize(candidate["content"])),
            )

        ranked: list[dict] = []
        all_candidates: list[dict] = []
        for candidate in candidate_map.values():
            candidate["rerank_score"] = 0.7 * candidate["vector_score"] + 0.3 * candidate["lexical_score"]
            all_candidates.append(candidate)
            if candidate["vector_score"] >= similarity_threshold or candidate["lexical_score"] >= 0.2:
                ranked.append(candidate)

        if not ranked:
            all_candidates.sort(key=lambda item: item["rerank_score"], reverse=True)
            return all_candidates[:top_k]

        ranked.sort(key=lambda item: item["rerank_score"], reverse=True)
        return ranked[:top_k]

    def _grounded_generate(self, question: str, chunks: list[dict], tenant_id: str = "") -> tuple[str, bool]:
        if not chunks:
            return (
                "I could not find grounded FAQ evidence for that question. Please refine the question or ingest more tenant data.",
                False,
            )

        context_lines = [
            f"[{index + 1}] ({chunk['source']}) {chunk['content']}"
            for index, chunk in enumerate(chunks)
        ]
        context = "\n\n".join(context_lines)

        llm = self._llm
        customer_label = (tenant_id or "the company").strip()
        prompt = [
            (
                "system",
                f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. "
                "Answer concisely and factually.",
            ),
            (
                "human",
                f"Question: {question}\n\nContext:\n{context}",
            ),
        ]
        result = llm.invoke(prompt)
        answer = str(result.content).strip()
        grounded = bool(answer) and "do not know" not in answer.lower()
        return answer or "No answer generated.", grounded

    def _tokenize(self, text: str) -> set[str]:
        return set(re.findall(r"[a-z0-9]+", text.lower()))

    def _lexical_score(self, query_tokens: set[str], chunk_tokens: set[str]) -> float:
        if not query_tokens or not chunk_tokens:
            return 0.0
        overlap = query_tokens.intersection(chunk_tokens)
        return len(overlap) / len(query_tokens)

    def query_stream(self, request: RetrievalQueryRequest) -> Generator[str, None, None]:
        """Stream retrieval query response as SSE events."""
        transformed_query = self._transform_query(request.question, request.queryContext)
        ranked_chunks = self._retrieve_and_rerank(
            transformed_query=transformed_query,
            tenant_id=request.tenantId,
            top_k=request.topK,
            similarity_threshold=request.similarityThreshold,
        )

        response_chunks = [
            {"rank": i + 1, "source": c["source"], "chunkNumber": c["chunk_number"], "score": round(c["rerank_score"], 4), "excerpt": c["content"]}
            for i, c in enumerate(ranked_chunks)
        ]

        if not ranked_chunks:
            yield sse_event("meta", {
                "tenantId": request.tenantId, "question": request.question,
                "transformedQuery": transformed_query, "strategy": "query-transform+hybrid-retrieval+rerank+grounded-generation",
                "chunksUsed": 0, "grounded": False, "chunks": [],
            })
            yield sse_event("done", {"answer": "I could not find grounded FAQ evidence for that question."})
            return

        context_lines = [f"[{i + 1}] ({c['source']}) {c['content']}" for i, c in enumerate(ranked_chunks)]
        context = "\n\n".join(context_lines)
        customer_label = (request.tenantId or "the company").strip()
        messages = [
            ("system", f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. Answer concisely and factually."),
            ("human", f"Question: {request.question}\n\nContext:\n{context}"),
        ]
        yield from stream_llm_response(
            self._llm, messages,
            metadata={
                "tenantId": request.tenantId, "question": request.question,
                "transformedQuery": transformed_query, "strategy": "query-transform+hybrid-retrieval+rerank+grounded-generation",
                "chunksUsed": len(response_chunks), "grounded": True, "chunks": response_chunks,
            },
        )


pipeline = RetrievalPipeline()
