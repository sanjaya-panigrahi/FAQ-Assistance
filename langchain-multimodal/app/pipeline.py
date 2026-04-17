import threading

from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.vectorstores import FAISS

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import VisionRagResponse


class MultimodalPipeline:
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

    def ask(self, question: str, image_description: str) -> VisionRagResponse:
        self.ensure_index()
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        docs = self._vector_store.similarity_search(question, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)

        prompt = (
            "You are a multimodal-style support assistant. Use FAQ context and optional image hints.\n\n"
            f"Question: {question}\n\n"
            f"Image Description: {image_description or 'No image context provided'}\n\n"
            f"FAQ Context:\n{context}"
        )

        answer = llm.invoke(prompt).content
        return VisionRagResponse(answer=str(answer), chunksUsed=len(docs), strategy="multimodal-context")


pipeline = MultimodalPipeline()
