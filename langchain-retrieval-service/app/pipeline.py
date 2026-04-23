import re
import time
import json

import chromadb
import redis

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RetrievedChunk, RetrievalQueryRequest, RetrievalQueryResponse


class RetrievalPipeline:
    def __init__(self) -> None:
        self._redis = redis.Redis(
            host=settings.redis_host,
            port=settings.redis_port,
            decode_responses=True,
        )

    def health(self) -> dict:
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            client.heartbeat()
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
        normalized = query.lower()
        expansions: list[str] = []

        if "return" in normalized or "refund" in normalized:
            expansions.append("return policy refund window")
        if "shipping" in normalized or "delivery" in normalized:
            expansions.append("shipping time delivery options")
        if "warranty" in normalized or "guarantee" in normalized:
            expansions.append("warranty coverage support")

        if query_context:
            expansions.append(query_context.strip())

        if expansions:
            return f"{query} {' '.join(expansions)}".strip()
        return query

    def _retrieve_and_rerank(
        self,
        transformed_query: str,
        tenant_id: str,
        top_k: int,
        similarity_threshold: float,
    ) -> list[dict]:
        collection_name = f"{settings.chroma_collection_prefix}{tenant_id or 'default'}"
        client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=client,
            collection_name=collection_name,
            embedding_function=embeddings,
        )

        candidate_map: dict[tuple[str, int | None, str], dict] = {}
        vector_hits = vector_store.similarity_search_with_relevance_scores(
            transformed_query,
            k=max(top_k * 3, 8),
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

        collection = client.get_collection(collection_name)
        lexical_query_tokens = self._tokenize(transformed_query)
        raw_chunks = collection.get(include=["documents", "metadatas"])
        documents = raw_chunks.get("documents") or []
        metadatas = raw_chunks.get("metadatas") or []

        for index, content in enumerate(documents):
            metadata = metadatas[index] if index < len(metadatas) else {}
            source = str((metadata or {}).get("source", "unknown"))
            chunk_number = (metadata or {}).get("chunk_number")
            key = (source, chunk_number, content[:120])
            candidate = candidate_map.setdefault(
                key,
                {
                    "content": content,
                    "source": source,
                    "chunk_number": chunk_number,
                    "vector_score": 0.0,
                    "lexical_score": 0.0,
                },
            )
            candidate["lexical_score"] = max(
                candidate["lexical_score"],
                self._lexical_score(lexical_query_tokens, self._tokenize(content)),
            )

        ranked: list[dict] = []
        for candidate in candidate_map.values():
            candidate["rerank_score"] = 0.7 * candidate["vector_score"] + 0.3 * candidate["lexical_score"]
            if candidate["vector_score"] >= similarity_threshold or candidate["lexical_score"] >= 0.2:
                ranked.append(candidate)

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

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        customer_label = (tenant_id or "the company").strip()
        prompt = [
            (
                "system",
                f"You are a support assistant for {customer_label}. Answer only with facts present in the provided context. If context is insufficient, explicitly say you do not know.",
            ),
            (
                "human",
                f"Question: {question}\n\nContext:\n{context}\n\nReturn a concise answer and cite chunk numbers in brackets.",
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


pipeline = RetrievalPipeline()
