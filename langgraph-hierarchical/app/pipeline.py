import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


class HierarchicalPipeline:
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
        selected_section = self._select_section(question)
        query = f"{question} {selected_section}".strip()

        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )
        docs = vector_store.similarity_search(query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are hierarchical RAG assistant for MyTechStore. Use selected section and answer from context only.\n\n"
                f"Section selected: {selected_section}\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-hierarchy",
            orchestrationStrategy="langgraph-hierarchy-multistep",
            selectedSection=selected_section,
        )

    def _select_section(self, question: str) -> str:
        q = question.lower()
        if any(t in q for t in ["return", "refund", "replacement", "exchange"]):
            return "Returns and Refunds"
        if any(t in q for t in ["shipping", "delivery", "track", "dispatch"]):
            return "Shipping and Delivery"
        if any(t in q for t in ["warranty", "guarantee", "repair"]):
            return "Warranty and Support"
        return "General FAQ"


pipeline = HierarchicalPipeline()
