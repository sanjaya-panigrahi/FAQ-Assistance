import logging
import os
import time
from concurrent.futures import ThreadPoolExecutor
from typing import TypedDict

import chromadb
import requests

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, END

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


class CRAGState(TypedDict):
    question: str
    customer_id: str
    docs: list
    relevant_docs: list
    web_context: str
    relevance_ratio: float
    context: str
    answer: str
    strategy: str


class CorrectivePipeline:
    def __init__(self) -> None:
        self._chroma_client = get_chroma_client()
        self._embeddings = get_embeddings()
        self._llm = get_llm()
        self._grading_llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._graph = self._build_graph()
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def _build_graph(self) -> StateGraph:
        """Build the CRAG StateGraph: retrieve → grade → decide → generate."""
        graph = StateGraph(CRAGState)
        graph.add_node("retrieve", self._node_retrieve)
        graph.add_node("grade_documents", self._node_grade)
        graph.add_node("web_search", self._node_web_search)
        graph.add_node("generate", self._node_generate)

        graph.set_entry_point("retrieve")
        graph.add_edge("retrieve", "grade_documents")
        graph.add_conditional_edges(
            "grade_documents",
            self._should_web_search,
            {"web_search": "web_search", "generate": "generate"},
        )
        graph.add_edge("web_search", "generate")
        graph.add_edge("generate", END)
        return graph.compile()

    def _node_retrieve(self, state: CRAGState) -> dict:
        tenant = (state["customer_id"] or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(state["question"], k=6)
        return {"docs": docs}

    def _node_grade(self, state: CRAGState) -> dict:
        docs = state["docs"]
        if not docs:
            return {"relevant_docs": [], "relevance_ratio": 0.0}

        def _grade(doc):
            prompt = GRADING_PROMPT.format(question=state["question"], document=doc.page_content)
            try:
                result = self._grading_llm.invoke(prompt).content.strip().upper()
                return "RELEVANT" in result and "IRRELEVANT" not in result
            except Exception:
                return True

        with ThreadPoolExecutor(max_workers=min(len(docs), 6)) as pool:
            grades = list(pool.map(lambda d: _grade(d), docs))

        relevant = [doc for doc, rel in zip(docs, grades) if rel]
        ratio = len(relevant) / len(docs)
        return {"relevant_docs": relevant, "relevance_ratio": ratio}

    def _should_web_search(self, state: CRAGState) -> str:
        if state["relevance_ratio"] < 1.0:
            return "web_search"
        return "generate"

    def _node_web_search(self, state: CRAGState) -> dict:
        if not TAVILY_API_KEY:
            logger.debug("TAVILY_API_KEY not set — skipping web search")
            return {"web_context": ""}
        try:
            resp = requests.post(
                TAVILY_SEARCH_URL,
                json={"api_key": TAVILY_API_KEY, "query": state["question"], "max_results": 3,
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
            return {"web_context": "\n\n".join(parts)}
        except Exception as exc:
            logger.warning("Tavily web search failed: %s", exc)
            return {"web_context": ""}

    def _node_generate(self, state: CRAGState) -> dict:
        relevant_docs = state.get("relevant_docs", [])
        web_context = state.get("web_context", "")
        relevance_ratio = state.get("relevance_ratio", 0.0)

        context_parts = [doc.page_content for doc in relevant_docs]
        if web_context:
            context_parts.append(f"--- Web Search Results ---\n{web_context}")

        if relevance_ratio < 0.5:
            strategy = "crag+web-search-fallback" if web_context else "crag+low-relevance"
        elif relevance_ratio < 1.0:
            strategy = "crag+ambiguous-supplemented" if web_context else "crag+ambiguous-local"
        else:
            strategy = "crag+all-relevant"

        context = "\n\n".join(context_parts)
        if not context.strip():
            # Fallback: use original docs when grading filtered everything and no web search
            docs = state.get("docs", [])
            context = "\n\n".join(doc.page_content for doc in docs)
            strategy = "crag+fallback-to-original"

        if not context.strip():
            return {"answer": NO_CONTEXT_ANSWER, "strategy": strategy, "context": ""}

        answer = self._llm.invoke(
            [
                ("system",
                 "You are a FAQ assistant. Answer the user's question using ONLY the provided context below. "
                 "If web search results are included, prefer local FAQ context but use web results to supplement. "
                 "Answer concisely and factually."),
                ("human", f"Question: {state['question']}\n\nContext:\n{context}"),
            ]
        ).content
        return {"answer": str(answer), "strategy": strategy, "context": context}

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb", "crag": True, "stateGraph": True}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        cached = response_cache.get("langgraph-corrective", tenant, question)
        if cached:
            return RagResponse(**cached)

        # Run the CRAG StateGraph
        result = self._graph.invoke({
            "question": question,
            "customer_id": customer_id,
            "docs": [],
            "relevant_docs": [],
            "web_context": "",
            "relevance_ratio": 0.0,
            "context": "",
            "answer": "",
            "strategy": "",
        })

        relevant = result.get("relevant_docs", [])
        docs = result.get("docs", [])
        chunks_used = len(relevant) if relevant else len(docs)
        response = RagResponse(
            answer=result["answer"],
            chunksUsed=chunks_used,
            strategy=result["strategy"],
            orchestrationStrategy="langgraph-crag-stategraph",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="corrective",
            framework="langgraph", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=result.get("context", ""),
        )
        response_cache.put("langgraph-corrective", tenant, question, response.model_dump())
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
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "crag+no-retrieval", "orchestrationStrategy": "langgraph-crag-stategraph"})
            yield sse_event("done", {"answer": NO_CONTEXT_ANSWER})
            return

        # Grade documents for streaming
        relevant = []
        for doc in docs:
            prompt = GRADING_PROMPT.format(question=question, document=doc.page_content)
            try:
                result = self._grading_llm.invoke(prompt).content.strip().upper()
                if "RELEVANT" in result and "IRRELEVANT" not in result:
                    relevant.append(doc)
            except Exception:
                relevant.append(doc)

        relevance_ratio = len(relevant) / len(docs) if docs else 0
        web_context = ""
        if relevance_ratio < 1.0 and TAVILY_API_KEY:
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
                web_context = "\n\n".join(parts)
            except Exception:
                pass

        context_parts = [doc.page_content for doc in relevant]
        if web_context:
            context_parts.append(f"--- Web Search Results ---\n{web_context}")
        context = "\n\n".join(context_parts)

        if not context.strip():
            from ..streaming import sse_event
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "crag+empty-after-grading", "orchestrationStrategy": "langgraph-crag-stategraph"})
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
            metadata={"chunksUsed": len(relevant), "strategy": strategy, "orchestrationStrategy": "langgraph-crag-stategraph"},
        )

pipeline = CorrectivePipeline()
