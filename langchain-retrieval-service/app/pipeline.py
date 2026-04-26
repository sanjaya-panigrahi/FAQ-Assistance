import re
import time
import json
import logging
from functools import lru_cache
from typing import Any

import chromadb
import redis

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI
from langchain_core.documents import Document

from .analytics_client import post_analytics_event
from .cached_embeddings import CachedOpenAIEmbeddings
from .config import settings
from .schemas import RetrievedChunk, RetrievalQueryRequest, RetrievalQueryResponse

logger = logging.getLogger(__name__)


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
        self._bm25_cache: dict[str, tuple[Any, float]] = {}  # tenant -> (BM25Retriever, created_ts)
        self._bm25_cache_ttl = 300  # 5 minutes
        self._warmup()

    def _warmup(self) -> None:
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
        self._bm25_cache.clear()
        return 0

    # ──────────────────────────────────────────────
    # Main query entry-point
    # ──────────────────────────────────────────────
    def query(self, request: RetrievalQueryRequest) -> RetrievalQueryResponse:
        cache_key = (
            f"retrieval:{request.tenantId}:{request.question}:"
            f"{request.topK}:{request.similarityThreshold}:{request.queryContext}"
        )

        # ① Redis Cache Lookup
        try:
            cached = self._redis.get(cache_key)
            if cached:
                payload = json.loads(cached)
                return RetrievalQueryResponse.model_validate(payload)
        except Exception:
            pass

        collection_name = f"{settings.chroma_collection_prefix}{request.tenantId or 'default'}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection_name,
            embedding_function=self._embeddings,
        )

        retrieval_start = time.perf_counter()

        # ② MultiQueryRetriever — expand query into variants
        if settings.enable_multi_query:
            query_variants = self._multi_query_expand(request.question, request.queryContext)
        else:
            query_variants = [self._transform_query(request.question, request.queryContext)]

        transformed_query = query_variants[0]

        # ③ EnsembleRetriever + BM25 — or fall back to vector-only
        if settings.enable_ensemble_bm25:
            candidate_docs = self._ensemble_retrieve(
                vector_store=vector_store,
                query_variants=query_variants,
                tenant_id=request.tenantId,
            )
        else:
            candidate_docs = self._vector_retrieve(
                vector_store=vector_store,
                query=transformed_query,
                k=max(request.topK * 2, 6),
            )

        # ④ Deduplicate candidates
        candidate_docs = self._deduplicate(candidate_docs)

        # ⑤ CohereRerank — or fall back to hybrid scoring
        if settings.enable_cohere_rerank and settings.cohere_api_key:
            ranked_docs = self._cohere_rerank(transformed_query, candidate_docs)
        else:
            ranked_docs = self._hybrid_rerank(
                transformed_query, candidate_docs, request.similarityThreshold,
            )

        # ⑥ Adaptive k
        if settings.enable_adaptive_k:
            ranked_docs = self._adaptive_k(ranked_docs, request.topK)
        else:
            ranked_docs = ranked_docs[: request.topK]

        retrieval_latency_ms = int((time.perf_counter() - retrieval_start) * 1000)

        # ⑦ Grounded Generation
        generation_start = time.perf_counter()
        ranked_chunks = self._docs_to_chunk_dicts(ranked_docs)
        answer, grounded = self._grounded_generate(
            request.question, ranked_chunks, request.tenantId,
        )
        generation_latency_ms = int((time.perf_counter() - generation_start) * 1000)

        response_chunks = [
            RetrievedChunk(
                rank=index + 1,
                source=item["source"],
                chunkNumber=item["chunk_number"],
                score=round(item["score"], 4),
                excerpt=item["content"],
            )
            for index, item in enumerate(ranked_chunks)
        ]

        strategy = self._build_strategy_label()

        response = RetrievalQueryResponse(
            tenantId=request.tenantId,
            question=request.question,
            transformedQuery=transformed_query,
            queryVariants=query_variants if settings.enable_multi_query else None,
            strategy=strategy,
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

        # ⑧ Redis Cache STORE
        try:
            self._redis.setex(
                cache_key,
                settings.redis_ttl_seconds,
                response.model_dump_json(),
            )
        except Exception:
            pass

        return response

    # ──────────────────────────────────────────────
    # ② MultiQueryRetriever
    # ──────────────────────────────────────────────
    def _multi_query_expand(self, question: str, query_context: str | None) -> list[str]:
        original = self._transform_query(question, query_context)
        try:
            prompt = [
                (
                    "system",
                    "You are a helpful assistant that generates alternative search queries. "
                    "Given a user question, generate {count} alternative versions of the question "
                    "that would help retrieve relevant FAQ documents. Return ONLY the queries, "
                    "one per line, without numbering or bullet points.".format(
                        count=settings.multi_query_count,
                    ),
                ),
                ("human", f"Original question: {original}"),
            ]
            result = self._llm.invoke(prompt)
            variants = [
                line.strip()
                for line in str(result.content).strip().splitlines()
                if line.strip()
            ]
            all_queries = [original] + variants[: settings.multi_query_count]
            logger.info("multi_query_expand: %d variants generated", len(all_queries) - 1)
            return all_queries
        except Exception as exc:
            logger.warning("multi_query_expand failed, using original: %s", exc)
            return [original]

    # ──────────────────────────────────────────────
    # ③ EnsembleRetriever + BM25
    # ──────────────────────────────────────────────
    def _ensemble_retrieve(
        self,
        vector_store: Chroma,
        query_variants: list[str],
        tenant_id: str,
    ) -> list[Document]:
        from langchain_community.retrievers import BM25Retriever
        from langchain.retrievers import EnsembleRetriever

        k = settings.ensemble_candidate_k

        bm25_retriever = self._get_bm25_retriever(tenant_id, k)
        chroma_retriever = vector_store.as_retriever(search_kwargs={"k": k})

        ensemble = EnsembleRetriever(
            retrievers=[chroma_retriever, bm25_retriever],
            weights=[settings.ensemble_vector_weight, settings.ensemble_bm25_weight],
        )

        all_docs: list[Document] = []
        seen_contents: set[str] = set()

        for variant in query_variants:
            docs = ensemble.invoke(variant)
            for doc in docs:
                content_key = doc.page_content[:200]
                if content_key not in seen_contents:
                    seen_contents.add(content_key)
                    all_docs.append(doc)

        logger.info("ensemble_retrieve: %d unique candidates from %d queries", len(all_docs), len(query_variants))
        return all_docs

    def _get_bm25_retriever(self, tenant_id: str, k: int) -> Any:
        from langchain_community.retrievers import BM25Retriever

        cache_key = tenant_id or "default"
        now = time.time()

        if cache_key in self._bm25_cache:
            cached_retriever, created = self._bm25_cache[cache_key]
            if now - created < self._bm25_cache_ttl:
                cached_retriever.k = k
                return cached_retriever

        collection_name = f"{settings.chroma_collection_prefix}{cache_key}"
        try:
            collection = self._chroma_client.get_collection(collection_name)
            result = collection.get(include=["documents", "metadatas"])
        except Exception:
            logger.warning("BM25: collection %s not found, returning empty retriever", collection_name)
            return BM25Retriever.from_texts([""], k=k)

        texts = result.get("documents", [])
        metadatas = result.get("metadatas", [])
        if not texts:
            return BM25Retriever.from_texts([""], k=k)

        retriever = BM25Retriever.from_texts(texts=texts, metadatas=metadatas, k=k)
        self._bm25_cache[cache_key] = (retriever, now)
        logger.info("BM25 index built for tenant=%s with %d docs", cache_key, len(texts))
        return retriever

    # ──────────────────────────────────────────────
    # Vector-only retrieval fallback
    # ──────────────────────────────────────────────
    def _vector_retrieve(self, vector_store: Chroma, query: str, k: int) -> list[Document]:
        results = vector_store.similarity_search_with_relevance_scores(query, k=k)
        docs = []
        for doc, score in results:
            doc.metadata["vector_score"] = float(score)
            docs.append(doc)
        return docs

    # ──────────────────────────────────────────────
    # ⑤ CohereRerank
    # ──────────────────────────────────────────────
    def _cohere_rerank(self, query: str, documents: list[Document], top_n: int | None = None) -> list[Document]:
        from langchain_cohere import CohereRerank

        if not documents:
            return []

        top_n = top_n or settings.cohere_rerank_top_n

        try:
            compressor = CohereRerank(
                cohere_api_key=settings.cohere_api_key,
                top_n=min(top_n, len(documents)),
                model=settings.cohere_rerank_model,
            )
            reranked = compressor.compress_documents(documents, query)
            for doc in reranked:
                doc.metadata.setdefault("relevance_score", doc.metadata.get("relevance_score", 0.0))
            logger.info("cohere_rerank: %d → %d docs", len(documents), len(reranked))
            return list(reranked)
        except Exception as exc:
            logger.warning("cohere_rerank failed, falling back to input order: %s", exc)
            return documents

    # ──────────────────────────────────────────────
    # Hybrid rerank fallback (original scoring)
    # ──────────────────────────────────────────────
    def _hybrid_rerank(
        self,
        query: str,
        documents: list[Document],
        similarity_threshold: float,
    ) -> list[Document]:
        if not documents:
            return []

        query_tokens = self._tokenize(query)

        scored: list[tuple[Document, float]] = []
        for doc in documents:
            vector_score = doc.metadata.get("vector_score", 0.5)
            lexical = self._lexical_score(query_tokens, self._tokenize(doc.page_content))
            composite = 0.7 * vector_score + 0.3 * lexical
            doc.metadata["relevance_score"] = composite
            if vector_score >= similarity_threshold or lexical >= 0.2:
                scored.append((doc, composite))

        if not scored:
            for doc in documents:
                composite = doc.metadata.get("relevance_score", 0.0)
                scored.append((doc, composite))

        scored.sort(key=lambda x: x[1], reverse=True)
        return [doc for doc, _ in scored]

    # ──────────────────────────────────────────────
    # ⑥ Adaptive k
    # ──────────────────────────────────────────────
    def _adaptive_k(self, documents: list[Document], top_k: int) -> list[Document]:
        if not documents:
            return documents

        top_score = documents[0].metadata.get("relevance_score", 0.0)
        if top_score >= settings.adaptive_k_threshold:
            k = min(settings.adaptive_k_high, top_k)
        else:
            k = min(settings.adaptive_k_low, top_k)

        logger.info(
            "adaptive_k: top_score=%.3f threshold=%.2f → k=%d",
            top_score, settings.adaptive_k_threshold, k,
        )
        return documents[:k]

    # ──────────────────────────────────────────────
    # Helpers
    # ──────────────────────────────────────────────
    def _transform_query(self, question: str, query_context: str | None) -> str:
        query = question.strip()
        if query_context:
            return f"{query} {query_context.strip()}"
        return query

    def _deduplicate(self, documents: list[Document]) -> list[Document]:
        seen: set[str] = set()
        unique: list[Document] = []
        for doc in documents:
            source = str(doc.metadata.get("source", ""))
            chunk_num = doc.metadata.get("chunk_number", "")
            key = f"{source}:{chunk_num}:{doc.page_content[:120]}"
            if key not in seen:
                seen.add(key)
                unique.append(doc)
        return unique

    def _docs_to_chunk_dicts(self, documents: list[Document]) -> list[dict]:
        return [
            {
                "content": doc.page_content,
                "source": str(doc.metadata.get("source", "unknown")),
                "chunk_number": doc.metadata.get("chunk_number"),
                "score": doc.metadata.get("relevance_score", 0.0),
            }
            for doc in documents
        ]

    def _grounded_generate(self, question: str, chunks: list[dict], tenant_id: str = "") -> tuple[str, bool]:
        if not chunks:
            return (
                "I could not find grounded FAQ evidence for that question. "
                "Please refine the question or ingest more tenant data.",
                False,
            )

        context_lines = [
            f"[{index + 1}] ({chunk['source']}) {chunk['content']}"
            for index, chunk in enumerate(chunks)
        ]
        context = "\n\n".join(context_lines)

        customer_label = (tenant_id or "the company").strip()
        prompt = [
            (
                "system",
                f"You are a FAQ assistant for {customer_label}. "
                "Answer the user's question using ONLY the provided FAQ context below. "
                "Answer concisely and factually.",
            ),
            ("human", f"Question: {question}\n\nContext:\n{context}"),
        ]
        result = self._llm.invoke(prompt)
        answer = str(result.content).strip()
        grounded = bool(answer) and "do not know" not in answer.lower()
        return answer or "No answer generated.", grounded

    def _build_strategy_label(self) -> str:
        parts: list[str] = []
        if settings.enable_multi_query:
            parts.append("multi-query")
        else:
            parts.append("query-transform")
        if settings.enable_ensemble_bm25:
            parts.append("ensemble(bm25+chroma)")
        else:
            parts.append("hybrid-retrieval")
        if settings.enable_cohere_rerank and settings.cohere_api_key:
            parts.append("cohere-rerank")
        else:
            parts.append("rerank")
        if settings.enable_adaptive_k:
            parts.append("adaptive-k")
        parts.append("grounded-generation")
        return "+".join(parts)

    def _tokenize(self, text: str) -> set[str]:
        return set(re.findall(r"[a-z0-9]+", text.lower()))

    def _lexical_score(self, query_tokens: set[str], chunk_tokens: set[str]) -> float:
        if not query_tokens or not chunk_tokens:
            return 0.0
        overlap = query_tokens.intersection(chunk_tokens)
        return len(overlap) / len(query_tokens)


pipeline = RetrievalPipeline()
