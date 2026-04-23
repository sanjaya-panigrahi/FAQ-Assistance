import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


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

        docs = vector_store.similarity_search(question, k=6)
        if len(docs) < 2:
            retry_query = f"{question} return policy warranty shipping payment support"
            docs = vector_store.similarity_search(retry_query, k=6)
            quality = "weak-retried"
        else:
            quality = "good"

        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langgraph-retry-nodes",
            )

        context = "\n\n".join(doc.page_content for doc in docs)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. Answer using ONLY the FAQ context provided below. "
                "If the context contains a general policy (e.g. return policy, warranty), apply it directly to the specific product the user asks about. "
                "Do not say the information is missing if a general policy covers it. "
                "Do not invent facts or add caveats not present in the context.\n\n"
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
