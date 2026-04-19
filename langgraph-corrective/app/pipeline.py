import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


class CorrectivePipeline:
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
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )

        docs = vector_store.similarity_search(question, k=4)
        if len(docs) < 2:
            retry_query = f"{question} return policy warranty shipping payment support"
            docs = vector_store.similarity_search(retry_query, k=4)
            quality = "weak-retried"
        else:
            quality = "good"

        context = "\n\n".join(doc.page_content for doc in docs)
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. If retrieval quality is weak, respond cautiously and mention uncertainty.\n\n"
                f"Retrieval quality: {quality}\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-retry",
            orchestrationStrategy="langgraph-retry-nodes",
        )


pipeline = CorrectivePipeline()
