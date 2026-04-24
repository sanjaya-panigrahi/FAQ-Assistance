import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class AgenticPipeline:
    def health(self) -> dict:
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception:
            return {"status": "DEGRADED", "backend": "chromadb"}

    def rebuild_index(self) -> int:
        return 0  # Index managed by faq-ingestion service (port 9000)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        collection_name = f"{settings.chroma_collection_prefix}{customer_id or 'default'}"
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
            vector_store = Chroma(
                client=client,
                collection_name=collection_name,
                embedding_function=embeddings,
            )
            retriever = vector_store.as_retriever(search_kwargs={"k": 6})
        except Exception as exc:
            raise RuntimeError(f"ChromaDB connection failed for collection {collection_name}") from exc

        docs = retriever.invoke(question)
        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langchain-agent",
            )

        combined_context = "\n\n".join(doc.page_content for doc in docs)

        customer_label = (customer_id or "the company").strip()
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)

        result = llm.invoke(
            [
                (
                    "system",
                    f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. "
                    "Answer concisely and factually.",
                ),
                (
                    "human",
                    f"Question: {question}\n\nFAQ Context:\n{combined_context}",
                ),
            ]
        )
        answer = str(result.content).strip()

        return RagResponse(
            answer=answer or "No answer generated.",
            chunksUsed=len(docs),
            strategy="chroma-direct+langchain-llm",
            orchestrationStrategy="langchain-agent",
        )




pipeline = AgenticPipeline()
