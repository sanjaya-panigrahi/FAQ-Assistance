import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


class AgenticPipeline:
    def health(self) -> dict:
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        route = self._route(question)
        retrieval_query = self._expand_query(question, route)

        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )
        docs = vector_store.similarity_search(retrieval_query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a MyTechStore support assistant. Follow route intent and answer from FAQ context only.\n\n"
                f"Route: {route}\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-routing",
            orchestrationStrategy="langgraph-multistep-routing",
        )

    def _route(self, question: str) -> str:
        q = question.lower()
        if any(t in q for t in ["return", "refund", "replace", "warranty"]):
            return "policy"
        if any(t in q for t in ["delivery", "shipping", "track", "dispatch"]):
            return "logistics"
        return "general"

    def _expand_query(self, question: str, route: str) -> str:
        if route == "policy":
            return f"{question} return policy refund replacement warranty"
        if route == "logistics":
            return f"{question} shipping delivery tracking"
        return question


pipeline = AgenticPipeline()
