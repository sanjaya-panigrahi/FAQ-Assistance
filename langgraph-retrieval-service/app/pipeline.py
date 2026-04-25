import re
import time
import json
from typing import TypedDict

import chromadb
import redis

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import END, START, StateGraph

from .analytics_client import post_analytics_event
from .config import settings
from .schemas import RetrievedChunk, RetrievalQueryRequest, RetrievalQueryResponse


class RetrievalState(TypedDict, total=False):
    request: RetrievalQueryRequest
    transformed_query: str
    candidates: dict
    ranked_chunks: list[dict]
    answer: str
    grounded: bool
    generation_latency_ms: int


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
        self._embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._warmup()

        graph = StateGraph(RetrievalState)
        graph.add_node("transform", self._node_transform_query)
        graph.add_node("hybrid_retrieve", self._node_hybrid_retrieve)
        graph.add_node("rerank", self._node_rerank)
        graph.add_node("generate", self._node_generate)

        graph.add_edge(START, "transform")
        graph.add_edge("transform", "hybrid_retrieve")
        graph.add_edge("hybrid_retrieve", "rerank")
        graph.add_edge("rerank", "generate")
        graph.add_edge("generate", END)

        self._graph = graph.compile()

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
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def query(self, request: RetrievalQueryRequest) -> RetrievalQueryResponse:
        cache_key = f"retrieval:{request.tenantId}:{request.question}:{request.topK}:{request.similarityThreshold}:{request.queryContext}"
        try:
            cached = self._redis.get(cache_key)
            if cached:
                payload = json.loads(cached)
                return RetrievalQueryResponse.model_validate(payload)
        except Exception:
            pass

        total_start = time.perf_counter()
        final_state = self._graph.invoke({"request": request})
        total_latency_ms = int((time.perf_counter() - total_start) * 1000)

        ranked_chunks = final_state.get("ranked_chunks", [])
        answer = str(final_state.get("answer", "No answer generated.")).strip()
        grounded = bool(final_state.get("grounded", False))

        generation_latency_ms = int(final_state.get("generation_latency_ms", 0))
        retrieval_latency_ms = max(0, total_latency_ms - generation_latency_ms)

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
            transformedQuery=str(final_state.get("transformed_query", request.question)),
            strategy="query-transform+hybrid-retrieval+rerank+grounded-generation(langgraph)",
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
            framework="langgraph", strategy=response.strategy,
            latency_ms=total_latency_ms,
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

    def _node_transform_query(self, state: RetrievalState) -> RetrievalState:
        request = state["request"]
        transformed_query = request.question.strip()
        if request.queryContext:
            transformed_query = f"{transformed_query} {request.queryContext.strip()}"
        return {"transformed_query": transformed_query}

    def _node_hybrid_retrieve(self, state: RetrievalState) -> RetrievalState:
        request = state["request"]
        transformed_query = state["transformed_query"]
        collection_name = f"{settings.chroma_collection_prefix}{request.tenantId or 'default'}"

        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection_name,
            embedding_function=self._embeddings,
        )

        candidate_map: dict[tuple[str, int | None, str], dict] = {}
        vector_hits = vector_store.similarity_search_with_relevance_scores(
            transformed_query,
            k=max(request.topK * 2, 6),
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

        return {"candidates": candidate_map}

    def _node_rerank(self, state: RetrievalState) -> RetrievalState:
        request = state["request"]
        candidate_map = state.get("candidates", {})
        ranked: list[dict] = []
        all_candidates: list[dict] = []

        for candidate in candidate_map.values():
            candidate["rerank_score"] = 0.7 * candidate["vector_score"] + 0.3 * candidate["lexical_score"]
            all_candidates.append(candidate)
            if candidate["vector_score"] >= request.similarityThreshold or candidate["lexical_score"] >= 0.15:
                ranked.append(candidate)

        if not ranked:
            all_candidates.sort(key=lambda item: item["rerank_score"], reverse=True)
            return {"ranked_chunks": all_candidates[: request.topK]}

        ranked.sort(key=lambda item: item["rerank_score"], reverse=True)
        return {"ranked_chunks": ranked[: request.topK]}

    def _node_generate(self, state: RetrievalState) -> RetrievalState:
        request = state["request"]
        chunks = state.get("ranked_chunks", [])
        if not chunks:
            return {
                "answer": "I could not find grounded FAQ evidence for that question. Please refine the question or ingest more tenant data.",
                "grounded": False,
                "generation_latency_ms": 0,
            }

        generation_start = time.perf_counter()
        context = "\n\n".join(
            f"[{index + 1}] ({chunk['source']}) {chunk['content']}"
            for index, chunk in enumerate(chunks)
        )
        llm = self._llm
        customer_label = (request.tenantId or "the company").strip()
        result = llm.invoke(
            [
                (
                    "system",
                    f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. "
                    "Answer concisely and factually.",
                ),
                (
                    "human",
                    f"Question: {request.question}\n\nContext:\n{context}",
                ),
            ]
        )
        answer = str(result.content).strip()
        grounded = bool(answer) and "do not know" not in answer.lower()
        return {
            "answer": answer or "No answer generated.",
            "grounded": grounded,
            "generation_latency_ms": int((time.perf_counter() - generation_start) * 1000),
        }

    def _tokenize(self, text: str) -> set[str]:
        return set(re.findall(r"[a-z0-9]+", text.lower()))

    def _lexical_score(self, query_tokens: set[str], chunk_tokens: set[str]) -> float:
        if not query_tokens or not chunk_tokens:
            return 0.0
        overlap = query_tokens.intersection(chunk_tokens)
        return len(overlap) / len(query_tokens)


pipeline = RetrievalPipeline()
