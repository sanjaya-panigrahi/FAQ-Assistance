import logging
import time

import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI

from collections.abc import Generator

from ..analytics_client import post_analytics_event
from ..cached_embeddings import CachedOpenAIEmbeddings
from ..config import settings
from ..http_pool import get_chroma_client, get_embeddings, get_llm
from ..reranker import rerank_documents
from ..response_cache import response_cache
from ..schemas import RagResponse
from ..streaming import stream_llm_response

logger = logging.getLogger(__name__)

NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)

SECTION_CLASSIFIER_PROMPT = (
    "Given these FAQ document summaries, identify which topic section is most relevant to the question.\n"
    "Respond with ONLY the section number (1, 2, 3, etc.).\n\n"
    "Question: {question}\n\nSections:\n{sections}"
)


class HierarchicalPipeline:
    def __init__(self) -> None:
        self._chroma_client = get_chroma_client()
        self._embeddings = get_embeddings()
        self._llm = get_llm()
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb", "hierarchical": True}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def _two_phase_retrieve(self, question: str, tenant: str) -> tuple[list, str]:
        """Two-phase hierarchical retrieval:
        Phase 1: Broad retrieval (k=10) to discover topic clusters
        Phase 2: LLM selects best cluster, rerank within it
        Returns (final_docs, selected_section)
        """
        collection = f"{settings.chroma_collection_prefix}{tenant}"
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )

        # Phase 1: Broad retrieval
        broad_docs = vector_store.similarity_search(question, k=10)
        if not broad_docs:
            return [], "none"

        # Group docs by content similarity (extract first line as section proxy)
        sections = {}
        for i, doc in enumerate(broad_docs):
            first_line = doc.page_content.split("\n")[0][:80].strip()
            section_key = first_line if first_line else f"Section {i+1}"
            if section_key not in sections:
                sections[section_key] = []
            sections[section_key].append(doc)

        if len(sections) <= 1:
            # All docs in one section — just rerank
            reranked = rerank_documents(self._llm, question, broad_docs, top_k=4)
            return reranked, list(sections.keys())[0] if sections else "general"

        # Phase 2: LLM picks the best section
        section_list = list(sections.keys())
        section_summaries = "\n".join(
            f"{i+1}. {key} ({len(sections[key])} docs)"
            for i, key in enumerate(section_list)
        )
        try:
            prompt = SECTION_CLASSIFIER_PROMPT.format(question=question, sections=section_summaries)
            result = self._llm.invoke(prompt).content.strip()
            selected_idx = int(result.split()[0]) - 1
            selected_idx = max(0, min(selected_idx, len(section_list) - 1))
            selected_section = section_list[selected_idx]
        except Exception:
            selected_section = section_list[0]

        # Get docs from selected section + top docs from other sections
        selected_docs = sections[selected_section]
        other_docs = [d for key in sections if key != selected_section for d in sections[key]][:2]
        candidate_docs = selected_docs + other_docs

        # Rerank the candidates
        final_docs = rerank_documents(self._llm, question, candidate_docs, top_k=4)
        return final_docs, selected_section

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        cached = response_cache.get("langchain-hierarchical", tenant, question)
        if cached:
            return RagResponse(**cached)

        docs, selected_section = self._two_phase_retrieve(question, tenant)

        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="hierarchical+no-retrieval",
                orchestrationStrategy="langchain-hierarchical-2phase",
            )

        context = "\n\n".join(doc.page_content for doc in docs)

        customer_label = (tenant or "the company").strip()
        answer = self._llm.invoke(
            [
                (
                    "system",
                    f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. "
                    "Answer concisely and factually.",
                ),
                (
                    "human",
                    f"Question: {question}\n\nFAQ Context:\n{context}",
                ),
            ]
        ).content

        response = RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy=f"hierarchical+2phase-section:{selected_section[:30]}",
            orchestrationStrategy="langchain-hierarchical-2phase",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="hierarchical",
            framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=context,
        )
        response_cache.put("langchain-hierarchical", tenant, question, response.model_dump())
        return response

    def ask_stream(self, question: str, customer_id: str | None = None) -> Generator[str, None, None]:
        tenant = (customer_id or "default").strip()

        docs, selected_section = self._two_phase_retrieve(question, tenant)

        if not docs:
            from ..streaming import sse_event
            yield sse_event("meta", {"chunksUsed": 0, "strategy": "hierarchical+no-retrieval", "orchestrationStrategy": "langchain-hierarchical-2phase"})
            yield sse_event("done", {"answer": NO_CONTEXT_ANSWER})
            return

        context = "\n\n".join(doc.page_content for doc in docs)
        customer_label = (tenant or "the company").strip()
        strategy = f"hierarchical+2phase-section:{selected_section[:30]}"
        messages = [
            ("system", f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. Answer concisely and factually."),
            ("human", f"Question: {question}\n\nFAQ Context:\n{context}"),
        ]
        yield from stream_llm_response(
            self._llm, messages,
            metadata={"chunksUsed": len(docs), "strategy": strategy, "orchestrationStrategy": "langchain-hierarchical-2phase"},
        )


pipeline = HierarchicalPipeline()
