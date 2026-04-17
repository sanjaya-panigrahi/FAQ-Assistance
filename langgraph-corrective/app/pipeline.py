import threading
from typing import Literal, TypedDict

from langchain_community.vectorstores import FAISS
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import END, StateGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class CorrectiveState(TypedDict, total=False):
    question: str
    query: str
    attempt: int
    context: str
    chunks_used: int
    quality: str
    answer: str


class CorrectivePipeline:
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
        final_state = self._graph.invoke({"question": question, "query": question, "attempt": 0})
        return RagResponse(
            answer=final_state.get("answer", "No answer generated."),
            chunksUsed=int(final_state.get("chunks_used", 0)),
            strategy="langgraph-explicit-retry",
        )

    def _build_graph(self):
        graph = StateGraph(CorrectiveState)
        graph.add_node("retrieve_step", self._retrieve_node)
        graph.add_node("evaluate_step", self._evaluate_node)
        graph.add_node("rewrite_step", self._rewrite_node)
        graph.add_node("answer_step", self._answer_node)

        graph.set_entry_point("retrieve_step")
        graph.add_edge("retrieve_step", "evaluate_step")
        graph.add_conditional_edges("evaluate_step", self._route_after_evaluation, {
            "rewrite": "rewrite_step",
            "answer": "answer_step",
        })
        graph.add_edge("rewrite_step", "retrieve_step")
        graph.add_edge("answer_step", END)
        return graph.compile()

    def _retrieve_node(self, state: CorrectiveState) -> CorrectiveState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")
        docs = self._vector_store.similarity_search(state.get("query", state["question"]), k=4)
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"context": context, "chunks_used": len(docs)}

    def _evaluate_node(self, state: CorrectiveState) -> CorrectiveState:
        quality = "good" if state.get("chunks_used", 0) >= 2 else "weak"
        return {"quality": quality}

    def _route_after_evaluation(self, state: CorrectiveState) -> Literal["rewrite", "answer"]:
        if state.get("quality") == "weak" and int(state.get("attempt", 0)) < 1:
            return "rewrite"
        return "answer"

    def _rewrite_node(self, state: CorrectiveState) -> CorrectiveState:
        attempt = int(state.get("attempt", 0)) + 1
        query = f"{state['question']} return policy warranty shipping payment support"
        return {"attempt": attempt, "query": query}

    def _answer_node(self, state: CorrectiveState) -> CorrectiveState:
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. If retrieval quality is weak, respond cautiously and say uncertainty. "
                "Otherwise answer concisely from context.\n\n"
                f"Retrieval quality: {state.get('quality', 'unknown')}\n"
                f"Question: {state['question']}\n\n"
                f"FAQ Context:\n{state.get('context', '')}"
            )
        ).content
        return {"answer": str(answer)}


pipeline = CorrectivePipeline()
