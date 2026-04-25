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


class HierarchicalPipeline:
    def __init__(self) -> None:
        self._chroma_client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
        self._embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=self._embeddings,
        )
        docs = vector_store.similarity_search(question, k=6)
        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langgraph-hierarchy-multistep",
            )
        context = "\n\n".join(doc.page_content for doc in docs)

        llm = self._llm
        customer_label = (tenant or "the company").strip()
        answer = llm.invoke(
            [
                (
                    "system",
                    f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. "
                    "Answer concisely and factually.",
                ),
                (
                    "human",
                    f"Question: {question}\n\nFAQ Context:\n{context}",
                ),
            ]
        ).content

        response = RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-hierarchy",
            orchestrationStrategy="langgraph-hierarchy-multistep",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="hierarchical",
            framework="langgraph", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=context,
        )
        return response


pipeline = HierarchicalPipeline()
