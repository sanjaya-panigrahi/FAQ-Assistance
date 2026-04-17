import threading

from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.vectorstores import FAISS

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class CorrectivePipeline:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._vector_store: FAISS | None = None

    def health(self) -> dict:
        return {"status": "UP", "indexed": self._vector_store is not None}

    def ensure_index(self) -> None:
        if self._vector_store is None:
            self.rebuild_index()

    def rebuild_index(self) -> int:
        documents = parse_faq_documents(settings.faq_source_file)
        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = FAISS.from_documents(documents, embeddings)

        with self._lock:
            self._vector_store = vector_store

        return len(documents)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        hits = self._vector_store.similarity_search_with_score(question, k=4)
        docs = [doc for doc, _ in hits]

        if not docs:
            fallback_answer = llm.invoke(
                "No FAQ context was retrieved. Respond with a safe short answer and suggest contacting support."
            ).content
            return RagResponse(answer=str(fallback_answer), chunksUsed=0, strategy="fallback-no-context")

        context = "\n\n".join(doc.page_content for doc in docs)
        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. Use FAQ context first. "
                "If context is weak, provide a cautious answer and say what is uncertain.\n\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(answer=str(answer), chunksUsed=len(docs), strategy="light-fallback")


pipeline = CorrectivePipeline()
