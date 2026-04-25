import logging
import time
from typing import TypedDict

import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, END

from collections.abc import Generator

from ..analytics_client import post_analytics_event
from ..cached_embeddings import CachedOpenAIEmbeddings
from ..http_pool import get_chroma_client, get_embeddings, get_llm
from ..config import settings
from ..reranker import rerank_documents
from ..response_cache import response_cache
from ..schemas import RagResponse
from ..streaming import stream_llm_response

logger = logging.getLogger(__name__)

NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class HierarchicalState(TypedDict):
    question: str
    customer_id: str
    broad_docs: list
    sections: dict
    selected_section: str
    final_docs: list
    context: str
    answer: str
    strategy: str


class HierarchicalPipeline:
    def __init__(self) -> None:
        self._chroma_client = get_chroma_client()
        self._embeddings = get_embeddings()
        self._llm = get_llm()
        self._graph = self._build_graph()
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def _build_graph(self) -> any:
        graph = StateGraph(HierarchicalState)
        graph.add_node("broad_retrieve", self._node_broad_retrieve)
        graph.add_node("classify_sections", self._node_classify_sections)
        graph.add_node("rerank_generate", self._node_rerank_generate)
        graph.set_entry_point("broad_retrieve")
        graph.add_edge("broad_retrieve", "classify_sections")
        graph.add_edge("classify_sections", "rerank_generate")
        graph.add_edge("rerank_generate", END)
        return graph.compile()

    def _node_broad_retrieve(self, state: HierarchicalState) -> dict:
        tenant = state["customer_id"]
        collection = f"{settings.chroma_collection_prefix}{tenant}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(state["question"], k=10)
        return {"broad_docs": docs}

    def _node_classify_sections(self, state: HierarchicalState) -> dict:
        docs = state["broad_docs"]
        if not docs:
            return {"sections": {}, "selected_section": "none", "final_docs": []}

        sections = {}
        for i, doc in enumerate(docs):
            first_line = doc.page_content.split("\n")[0][:80].strip()
            key = first_line if first_line else f"Section {i+1}"
            sections.setdefault(key, []).append(doc)

        if len(sections) <= 1:
            key = list(sections.keys())[0] if sections else "general"
            return {"sections": sections, "selected_section": key}

        section_list = list(sections.keys())
        summaries = "\n".join(f"{i+1}. {k} ({len(sections[k])} docs)" for i, k in enumerate(section_list))
        try:
            prompt = (
                f"Given these FAQ sections, which is most relevant to the question? "
                f"Respond with ONLY the section number.\n\nQuestion: {state['question']}\n\nSections:\n{summaries}"
            )
            result = self._llm.invoke(prompt).content.strip()
            idx = max(0, min(int(result.split()[0]) - 1, len(section_list) - 1))
            selected = section_list[idx]
        except Exception:
            selected = section_list[0]

        return {"sections": sections, "selected_section": selected}

    def _node_rerank_generate(self, state: HierarchicalState) -> dict:
        sections = state.get("sections", {})
        selected = state.get("selected_section", "none")

        if not sections or selected == "none":
            return {"final_docs": [], "context": "", "answer": NO_CONTEXT_ANSWER, "strategy": "hierarchical+no-retrieval"}

        selected_docs = sections.get(selected, [])
        other_docs = [d for k in sections if k != selected for d in sections[k]][:2]
        candidates = selected_docs + other_docs

        final_docs = rerank_documents(self._llm, state["question"], candidates, top_k=4)
        context = "\n\n".join(doc.page_content for doc in final_docs)

        tenant = state["customer_id"]
        customer_label = (tenant or "the company").strip()
        answer = self._llm.invoke([
            ("system", f"You are a FAQ assistant for {customer_label}. Answer using ONLY the FAQ context below. Be concise and factual."),
            ("human", f"Question: {state['question']}\n\nFAQ Context:\n{context}"),
        ]).content

        strategy = f"hierarchical+2phase-section:{selected[:30]}"
        return {"final_docs": final_docs, "context": context, "answer": str(answer), "strategy": strategy}

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb", "hierarchical": True}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        cached = response_cache.get("langgraph-hierarchical", tenant, question)
        if cached:
            return RagResponse(**cached)

        result = self._graph.invoke({
            "question": question,
            "customer_id": tenant,
            "broad_docs": [],
            "sections": {},
            "selected_section": "",
            "final_docs": [],
            "context": "",
            "answer": "",
            "strategy": "",
        })

        response = RagResponse(
            answer=result["answer"],
            chunksUsed=len(result["final_docs"]),
            strategy=result["strategy"],
            orchestrationStrategy="langgraph-hierarchical-2phase",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="hierarchical",
            framework="langgraph", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=result["context"],
        )
        response_cache.put("langgraph-hierarchical", tenant, question, response.model_dump())
        return response

    def ask_stream(self, question: str, customer_id: str | None = None) -> Generator[str, None, None]:
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(question, k=10)
        if not docs:
            from ..streaming import sse_event
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "hierarchical+no-retrieval", "orchestrationStrategy": "langgraph-hierarchical-2phase"})
            yield sse_event("done", {"answer": NO_CONTEXT_ANSWER})
            return

        # Two-phase inline for streaming
        sections = {}
        for i, doc in enumerate(docs):
            key = doc.page_content.split("\n")[0][:80].strip() or f"Section {i+1}"
            sections.setdefault(key, []).append(doc)

        if len(sections) > 1:
            section_list = list(sections.keys())
            summaries = "\n".join(f"{i+1}. {k} ({len(sections[k])} docs)" for i, k in enumerate(section_list))
            try:
                result = self._llm.invoke(f"Which section is most relevant? Respond with ONLY the number.\n\nQuestion: {question}\n\nSections:\n{summaries}").content.strip()
                idx = max(0, min(int(result.split()[0]) - 1, len(section_list) - 1))
                selected = section_list[idx]
            except Exception:
                selected = section_list[0]
            selected_docs = sections[selected]
            other_docs = [d for k in sections if k != selected for d in sections[k]][:2]
            candidates = selected_docs + other_docs
        else:
            selected = list(sections.keys())[0]
            candidates = docs

        final_docs = rerank_documents(self._llm, question, candidates, top_k=4)
        context = "\n\n".join(doc.page_content for doc in final_docs)
        strategy = f"hierarchical+2phase-section:{selected[:30]}"
        customer_label = (tenant or "the company").strip()
        messages = [
            ("system", f"You are a FAQ assistant for {customer_label}. Answer using ONLY the FAQ context below. Be concise and factual."),
            ("human", f"Question: {question}\n\nFAQ Context:\n{context}"),
        ]
        yield from stream_llm_response(
            self._llm, messages,
            metadata={"chunksUsed": len(final_docs), "strategy": strategy, "orchestrationStrategy": "langgraph-hierarchical-2phase"},
        )


pipeline = HierarchicalPipeline()
