import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor

import chromadb
import requests

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI

from collections.abc import Generator

from ..analytics_client import post_analytics_event
from ..cached_embeddings import CachedOpenAIEmbeddings
from ..config import settings
from ..http_pool import get_chroma_client, get_embeddings, get_llm
from ..response_cache import response_cache
from ..schemas import RagResponse
from ..streaming import stream_llm_response

logger = logging.getLogger(__name__)

NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)

GRADING_PROMPT = (
    "You are a relevance grader. Given a user question and a retrieved FAQ document, "
    "determine if the document contains information relevant to answering the question.\n"
    "Respond with ONLY one word: RELEVANT or IRRELEVANT.\n\n"
    "Question: {question}\n\nDocument:\n{document}"
)

TAVILY_API_KEY = os.getenv("TAVILY_API_KEY", "")
TAVILY_SEARCH_URL = "https://api.tavily.com/search"


class CorrectivePipeline:
    def __init__(self) -> None:
        self._chroma_client = get_chroma_client()
        self._embeddings = get_embeddings()
        self._llm = get_llm()
        self._grading_llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb", "crag": True}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def _grade_documents(self, question: str, docs: list) -> tuple[list, list]:
        """Grade each document for relevance using LLM-as-judge.
        Returns (relevant_docs, irrelevant_docs)."""
        def _grade(doc):
            prompt = GRADING_PROMPT.format(question=question, document=doc.page_content)
            try:
                result = self._grading_llm.invoke(prompt).content.strip().upper()
                is_relevant = "RELEVANT" in result and "IRRELEVANT" not in result
            except Exception:
                is_relevant = True  # on error, keep the doc
            return doc, is_relevant

        with ThreadPoolExecutor(max_workers=min(len(docs), 6)) as pool:
            results = list(pool.map(lambda d: _grade(d), docs))

        relevant = [doc for doc, rel in results if rel]
        irrelevant = [doc for doc, rel in results if not rel]
        return relevant, irrelevant

    def _web_search_fallback(self, question: str) -> str:
        """Search the web via Tavily API when local docs are insufficient."""
        if not TAVILY_API_KEY:
            logger.debug("TAVILY_API_KEY not set — skipping web search fallback")
            return ""
        try:
            resp = requests.post(
                TAVILY_SEARCH_URL,
                json={"api_key": TAVILY_API_KEY, "query": question, "max_results": 3,
                      "search_depth": "basic", "include_answer": True},
                timeout=10,
            )
            resp.raise_for_status()
            data = resp.json()
            parts = []
            if data.get("answer"):
                parts.append(f"Web Summary: {data['answer']}")
            for r in data.get("results", [])[:3]:
                parts.append(f"Source: {r.get('title', '')}\n{r.get('content', '')}")
            return "\n\n".join(parts)
        except Exception as exc:
            logger.warning("Tavily web search failed: %s", exc)
            return ""

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        cached = response_cache.get("langchain-corrective", tenant, question)
        if cached:
            return RagResponse(**cached)

        collection = f"{settings.chroma_collection_prefix}{tenant}"

        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(question, k=6)

        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="crag+no-retrieval",
                orchestrationStrategy="langchain-crag",
            )

        # --- CRAG: LLM-based relevance grading ---
        relevant_docs, irrelevant_docs = self._grade_documents(question, docs)
        relevance_ratio = len(relevant_docs) / len(docs) if docs else 0

        web_context = ""
        if relevance_ratio < 0.5:
            # INCORRECT: majority irrelevant — try web search
            web_context = self._web_search_fallback(question)
            strategy = "crag+web-search-fallback" if web_context else "crag+low-relevance"
        elif relevance_ratio < 1.0:
            # AMBIGUOUS: mixed relevance — supplement with web search
            web_context = self._web_search_fallback(question)
            strategy = "crag+ambiguous-supplemented" if web_context else "crag+ambiguous-local"
        else:
            # CORRECT: all relevant — use local docs only
            strategy = "crag+all-relevant"

        # Build context from relevant local docs + optional web context
        context_parts = [doc.page_content for doc in relevant_docs]
        if web_context:
            context_parts.append(f"--- Web Search Results ---\n{web_context}")
        context = "\n\n".join(context_parts)

        if not context.strip():
            # Fallback: use original docs when grading filtered everything and no web search
            context = "\n\n".join(doc.page_content for doc in docs)
            strategy = "crag+fallback-to-original"

        if not context.strip():
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="crag+empty-after-grading",
                orchestrationStrategy="langchain-crag",
            )

        llm = self._llm
        answer = llm.invoke(
            [
                (
                    "system",
                    "You are a FAQ assistant. Answer the user's question using ONLY the provided context below. "
                    "If web search results are included, prefer local FAQ context but use web results to supplement. "
                    "Answer concisely and factually.",
                ),
                (
                    "human",
                    f"Question: {question}\n\nContext:\n{context}",
                ),
            ]
        ).content

        chunks_used = len(relevant_docs) if relevant_docs else len(docs)
        response = RagResponse(
            answer=str(answer),
            chunksUsed=chunks_used,
            strategy=strategy,
            orchestrationStrategy="langchain-crag",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="corrective",
            framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=context,
        )
        response_cache.put("langchain-corrective", tenant, question, response.model_dump())
        return response

    def ask_stream(self, question: str, customer_id: str | None = None) -> Generator[str, None, None]:
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(question, k=6)
        if not docs:
            from ..streaming import sse_event
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "crag+no-retrieval", "orchestrationStrategy": "langchain-crag"})
            yield sse_event("done", {"answer": NO_CONTEXT_ANSWER})
            return

        # CRAG grading for streaming
        relevant_docs, _ = self._grade_documents(question, docs)
        relevance_ratio = len(relevant_docs) / len(docs) if docs else 0

        web_context = ""
        if relevance_ratio < 1.0:
            web_context = self._web_search_fallback(question)

        context_parts = [doc.page_content for doc in relevant_docs]
        if web_context:
            context_parts.append(f"--- Web Search Results ---\n{web_context}")
        context = "\n\n".join(context_parts)

        if not context.strip():
            from ..streaming import sse_event
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "crag+empty-after-grading", "orchestrationStrategy": "langchain-crag"})
            yield sse_event("done", {"answer": NO_CONTEXT_ANSWER})
            return

        strategy = "crag+web-supplemented" if web_context else "crag+local-only"
        messages = [
            ("system", "You are a FAQ assistant. Answer the user's question using ONLY the provided context below. "
             "If web search results are included, prefer local FAQ context but use web results to supplement. "
             "Answer concisely and factually."),
            ("human", f"Question: {question}\n\nContext:\n{context}"),
        ]
        yield from stream_llm_response(
            self._llm, messages,
            metadata={"chunksUsed": len(relevant_docs), "strategy": strategy, "orchestrationStrategy": "langchain-crag"},
        )


pipeline = CorrectivePipeline()
