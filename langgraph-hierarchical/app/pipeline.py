import threading
from typing import TypedDict

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
        return len(docs)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        final_state = self._graph.invoke({"question": question})
        return RagResponse(
            answer=final_state.get("answer", "No answer generated."),
            chunksUsed=int(final_state.get("chunks_used", 0)),
            strategy="langgraph-hierarchy-control",
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
        q = state["question"].lower()
        if any(t in q for t in ["return", "refund", "replace", "warranty"]):
            return {
                "hierarchy_level": "policy",
                "section_hint": "Returns and Warranty",
                "retrieval_query": f"{state['question']} returns refunds warranty",
            }
        if any(t in q for t in ["ship", "delivery", "track", "pickup"]):
            return {
                "hierarchy_level": "logistics",
                "section_hint": "Shipping and Delivery",
                "retrieval_query": f"{state['question']} shipping delivery tracking pickup",
            }
        return {
            "hierarchy_level": "general",
            "section_hint": "General FAQ",
            "retrieval_query": state["question"],
        }

    def _retrieve_node(self, state: HierarchicalState) -> HierarchicalState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        query = f"{state.get('retrieval_query', state['question'])} {state.get('section_hint', '')}"
        docs = self._vector_store.similarity_search(query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"context": context, "chunks_used": len(docs)}

    def _answer_node(self, state: HierarchicalState) -> HierarchicalState:
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a hierarchical RAG assistant. Use hierarchy and section hints before answering.\n\n"
                f"Hierarchy Level: {state.get('hierarchy_level', 'general')}\n"
                f"Section Hint: {state.get('section_hint', 'General FAQ')}\n"
                f"Question: {state['question']}\n\n"
                f"FAQ Context:\n{state.get('context', '')}"
            )
        ).content
        return {"answer": str(answer)}


pipeline = HierarchicalPipeline()
