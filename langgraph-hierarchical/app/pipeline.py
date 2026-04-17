import threading
from collections import OrderedDict
from typing import TypedDict

from langchain_core.documents import Document
from langchain_community.vectorstores import FAISS
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import END, StateGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class HierarchicalState(TypedDict, total=False):
    question: str
    hierarchy_level: str
    section_hint: str
    retrieval_query: str
    context: str
    chunks_used: int
    answer: str


class HierarchicalPipeline:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._vector_store: FAISS | None = None
        self._faq_entries: list[Document] = []
        self._section_headers: list[str] = []
        self._graph = self._build_graph()

    def health(self) -> dict:
        return {"status": "UP", "indexed": self._vector_store is not None}

    def ensure_index(self) -> None:
        if self._vector_store is None:
            self.rebuild_index()

    def rebuild_index(self) -> int:
        docs = parse_faq_documents(settings.faq_source_file)
        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = FAISS.from_documents(docs, embeddings)
        with self._lock:
            self._vector_store = vector_store
            self._faq_entries = docs
            self._section_headers = self._collect_section_headers(docs)
        return len(docs)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        final_state = self._graph.invoke({"question": question})
        return RagResponse(
            answer=final_state.get("answer", "No answer generated."),
            chunksUsed=int(final_state.get("chunks_used", 0)),
            strategy="langgraph-hierarchy-control",
            selectedSection=final_state.get("section_hint"),
        )

    def _build_graph(self):
        graph = StateGraph(HierarchicalState)
        graph.add_node("classify_step", self._classify_node)
        graph.add_node("retrieve_step", self._retrieve_node)
        graph.add_node("answer_step", self._answer_node)

        graph.set_entry_point("classify_step")
        graph.add_edge("classify_step", "retrieve_step")
        graph.add_edge("retrieve_step", "answer_step")
        graph.add_edge("answer_step", END)
        return graph.compile()

    def _classify_node(self, state: HierarchicalState) -> HierarchicalState:
        section_hint = self._select_section(state["question"])
        hierarchy_level = "general"
        retrieval_query = state["question"]

        if section_hint in {"Returns, Refunds, and Replacements", "Returns and Warranty", "Warranty and Protection Plans"}:
            hierarchy_level = "policy"
            retrieval_query = f"{state['question']} returns refunds warranty unopened defective"
        elif section_hint in {"Shipping and Delivery", "Shipping and Delivery Details"}:
            hierarchy_level = "logistics"
            retrieval_query = f"{state['question']} shipping delivery tracking pickup"

        return {
            "hierarchy_level": hierarchy_level,
            "section_hint": section_hint,
            "retrieval_query": retrieval_query,
        }

    def _retrieve_node(self, state: HierarchicalState) -> HierarchicalState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        query = f"{state.get('retrieval_query', state['question'])} {state.get('section_hint', '')}"
        vector_docs = self._vector_store.similarity_search(query, k=4)
        lexical_docs = self._lexical_hits(query)
        merged: OrderedDict[str, Document] = OrderedDict()
        ranked_docs = sorted(
            vector_docs + lexical_docs,
            key=lambda doc: self._document_score(state["question"], doc, state.get("section_hint")),
            reverse=True,
        )
        for doc in ranked_docs:
            merged.setdefault(doc.page_content, doc)
        docs = list(merged.values())[:4]
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"context": context, "chunks_used": len(docs)}

    def _answer_node(self, state: HierarchicalState) -> HierarchicalState:
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are hierarchical RAG assistant for MyTechStore. "
                f"Section selected: {state.get('section_hint', 'General FAQ')}. "
                "Answer only from context. If the context provides a general policy and no product-specific exception, use the general policy. "
                "Keep the answer concise and do not add unrelated policy notes or website references.\n\n"
                f"Hierarchy Level: {state.get('hierarchy_level', 'general')}\n"
                f"Section Hint: {state.get('section_hint', 'General FAQ')}\n"
                f"Question: {state['question']}\n\n"
                f"FAQ Context:\n{state.get('context', '')}"
            )
        ).content
        return {"answer": str(answer)}

    def _lexical_hits(self, query: str) -> list[Document]:
        ranked = sorted(
            self._faq_entries,
            key=lambda doc: self._document_score(query, doc, None),
            reverse=True,
        )
        return [doc for doc in ranked if self._document_score(query, doc, None) > 0][:4]

    def _document_score(self, question: str, doc: Document, selected_section: str | None) -> int:
        text = self._normalize(doc.page_content)
        section = str(doc.metadata.get("section", ""))
        tokens = [token for token in self._normalize(question).split() if token]

        score = 0
        for token in tokens:
            if token in text:
                score += 2
        if "return" in tokens and "policy" in tokens and "return policy" in text:
            score += 5
        if selected_section and section == selected_section:
            score += 8
        return score

    def _select_section(self, question: str) -> str:
        normalized = self._normalize(question)
        section_preferences = [
            (["return", "refund", "replace", "defect"], ["Returns, Refunds, and Replacements", "Returns and Warranty"]),
            (["warranty", "damage", "protection", "repair"], ["Warranty and Protection Plans", "Returns and Warranty"]),
            (["shipping", "delivery", "track", "pickup", "order status"], ["Shipping and Delivery Details", "Shipping and Delivery"]),
            (["order", "checkout", "cancel", "invoice", "payment"], ["Orders and Checkout", "Pricing and Payments"]),
            (["branch", "store", "support", "contact"], ["In-Store Services and Branch Support", "Branches and Support", "Support and Policies"]),
        ]

        for keywords, candidates in section_preferences:
            if any(keyword in normalized for keyword in keywords):
                for candidate in candidates:
                    if candidate in self._section_headers:
                        return candidate

        for section in self._section_headers:
            first_word = section.split()[0].lower()
            if first_word and first_word in normalized:
                return section

        return "General FAQ"

    def _collect_section_headers(self, docs: list[Document]) -> list[str]:
        headers: list[str] = []
        for doc in docs:
            section = str(doc.metadata.get("section", "")).strip()
            if section and section not in headers:
                headers.append(section)
        return headers

    def _normalize(self, value: str) -> str:
        return " ".join("".join(ch.lower() if ch.isalnum() else " " for ch in value).split())


pipeline = HierarchicalPipeline()
