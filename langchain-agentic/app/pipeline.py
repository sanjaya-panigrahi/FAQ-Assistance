import time

import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .analytics_client import post_analytics_event
from .config import settings
from .schemas import RagResponse


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class AgenticPipeline:
    def __init__(self) -> None:
        self._chroma_client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
        self._embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception:
            return {"status": "DEGRADED", "backend": "chromadb"}

    def rebuild_index(self) -> int:
        return 0  # Index managed by faq-ingestion service (port 9000)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        collection_name = f"{settings.chroma_collection_prefix}{customer_id or 'default'}"
        try:
            vector_store = Chroma(
                client=self._chroma_client,
                collection_name=collection_name,
                embedding_function=self._embeddings,
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
        result = self._llm.invoke(
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

        response = RagResponse(
            answer=answer or "No answer generated.",
            chunksUsed=len(docs),
            strategy="chroma-direct+langchain-llm",
            orchestrationStrategy="langchain-agent",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="agentic",
            framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=combined_context,
        )
        return response




pipeline = AgenticPipeline()
