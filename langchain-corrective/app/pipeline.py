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

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        if not docs:
            fallback_answer = llm.invoke(
                "No FAQ context was retrieved. Respond with a safe short answer and suggest contacting support."
            ).content
            return RagResponse(
                answer=str(fallback_answer),
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langchain-light-fallback",
            )

        context = "\n\n".join(doc.page_content for doc in docs)
        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. Use FAQ context first. "
                "If context is weak, provide a cautious answer and say what is uncertain.\n\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+light-fallback",
            orchestrationStrategy="langchain-light-fallback",
        )


pipeline = CorrectivePipeline()
