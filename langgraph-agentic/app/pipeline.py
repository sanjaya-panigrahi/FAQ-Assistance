import threading
from typing import Literal, TypedDict

from langchain_community.vectorstores import FAISS
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langgraph.graph import END, StateGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class AgenticState(TypedDict, total=False):
    question: str
    route: str
    retrieval_query: str
    context: str
    chunks_used: int
    answer: str


class AgenticPipeline:
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
            strategy="langgraph-multistep-routing",
        )

    def _build_graph(self):
        graph = StateGraph(AgenticState)
        graph.add_node("route_step", self._route_node)
        graph.add_node("retrieve_step", self._retrieve_node)
        graph.add_node("answer_step", self._answer_node)

        graph.set_entry_point("route_step")
        graph.add_edge("route_step", "retrieve_step")
        graph.add_edge("retrieve_step", "answer_step")
        graph.add_edge("answer_step", END)
        return graph.compile()

    def _route_node(self, state: AgenticState) -> AgenticState:
        question = state["question"]
        lower = question.lower()
        if any(t in lower for t in ["return", "refund", "replace", "warranty"]):
            route = "policy"
            retrieval_query = f"{question} return policy refund replacement warranty"
        elif any(t in lower for t in ["delivery", "shipping", "track", "dispatch"]):
            route = "logistics"
            retrieval_query = f"{question} shipping delivery tracking"
        else:
            route = "general"
            retrieval_query = question

        return {"route": route, "retrieval_query": retrieval_query}

    def _retrieve_node(self, state: AgenticState) -> AgenticState:
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        query = state.get("retrieval_query", state["question"])
        docs = self._vector_store.similarity_search(query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)
        return {"context": context, "chunks_used": len(docs)}

    def _answer_node(self, state: AgenticState) -> AgenticState:
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a MyTechStore support assistant. "
                "Follow the route intent and answer from FAQ context only. "
                "If context is insufficient, clearly say so.\n\n"
                f"Route: {state.get('route', 'general')}\n"
                f"Question: {state['question']}\n\n"
                f"FAQ Context:\n{state.get('context', '')}"
            )
        ).content
        return {"answer": str(answer)}


pipeline = AgenticPipeline()
