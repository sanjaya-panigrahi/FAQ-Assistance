import threading
from typing import Literal, TypedDict

from langchain_community.vectorstores import FAISS
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import END, StateGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import VisionRagResponse


class MultimodalState(TypedDict, total=False):
    question: str
    image_description: str
    extracted_signals: str
    image_valid: bool
    retrieval_query: str
    context: str
    chunks_used: int
    answer: str


class MultimodalPipeline:
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

    def ask(self, question: str, image_description: str) -> VisionRagResponse:
        self.ensure_index()
        final_state = self._graph.invoke({"question": question, "image_description": image_description})
        return VisionRagResponse(
            answer=final_state.get("answer", "No answer generated."),
            chunksUsed=int(final_state.get("chunks_used", 0)),
            strategy="langgraph-branching-multimodal",
        )

    def _build_graph(self):
        graph = StateGraph(MultimodalState)
        graph.add_node("extract_step", self._extract_node)
        graph.add_node("validate_step", self._validate_node)
        graph.add_node("retrieve_with_image_step", self._retrieve_with_image_node)
        graph.add_node("retrieve_text_only_step", self._retrieve_text_only_node)
        graph.add_node("answer_step", self._answer_node)

        graph.set_entry_point("extract_step")
        graph.add_edge("extract_step", "validate_step")
        graph.add_conditional_edges("validate_step", self._route_validation, {
            "with_image": "retrieve_with_image_step",
            "text_only": "retrieve_text_only_step",
        })
        graph.add_edge("retrieve_with_image_step", "answer_step")
        graph.add_edge("retrieve_text_only_step", "answer_step")
        graph.add_edge("answer_step", END)
        return graph.compile()

    def _extract_node(self, state: MultimodalState) -> MultimodalState:
        image_description = state.get("image_description", "").strip()
        extracted = image_description[:200] if image_description else "no-image-signal"
        return {"extracted_signals": extracted}

    def _validate_node(self, state: MultimodalState) -> MultimodalState:
        valid = state.get("extracted_signals", "") not in ("", "no-image-signal")
        return {"image_valid": valid}

    def _route_validation(self, state: MultimodalState) -> Literal["with_image", "text_only"]:
        return "with_image" if state.get("image_valid", False) else "text_only"

    def _retrieve_with_image_node(self, state: MultimodalState) -> MultimodalState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        retrieval_query = f"{state['question']} {state.get('extracted_signals', '')}"
        docs = self._vector_store.similarity_search(retrieval_query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"retrieval_query": retrieval_query, "context": context, "chunks_used": len(docs)}

    def _retrieve_text_only_node(self, state: MultimodalState) -> MultimodalState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        retrieval_query = state["question"]
        docs = self._vector_store.similarity_search(retrieval_query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"retrieval_query": retrieval_query, "context": context, "chunks_used": len(docs)}

    def _answer_node(self, state: MultimodalState) -> MultimodalState:
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a multimodal FAQ assistant. Use image hints only when valid; otherwise rely on FAQ context. "
                "If details are missing, mention uncertainty.\n\n"
                f"Question: {state['question']}\n"
                f"Image Valid: {state.get('image_valid', False)}\n"
                f"Image Signals: {state.get('extracted_signals', '')}\n\n"
                f"FAQ Context:\n{state.get('context', '')}"
            )
        ).content
        return {"answer": str(answer)}


pipeline = MultimodalPipeline()
