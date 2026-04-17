import threading

from langchain.storage import InMemoryStore
from langchain.retrievers import ParentDocumentRetriever
from langchain_text_splitters import RecursiveCharacterTextSplitter
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.vectorstores import FAISS

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class HierarchicalPipeline:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._retriever: ParentDocumentRetriever | None = None

    def health(self) -> dict:
        return {"status": "UP", "indexed": self._retriever is not None}

    def ensure_index(self) -> None:
        if self._retriever is None:
            self.rebuild_index()

    def rebuild_index(self) -> int:
        documents = parse_faq_documents(settings.faq_source_file)

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = FAISS.from_texts(["bootstrap"], embedding=embeddings)
        bootstrap_ids = list(vector_store.index_to_docstore_id.values())
        if bootstrap_ids:
            vector_store.delete(bootstrap_ids)

        parent_splitter = RecursiveCharacterTextSplitter(chunk_size=500, chunk_overlap=50)
        child_splitter = RecursiveCharacterTextSplitter(chunk_size=180, chunk_overlap=20)
        store = InMemoryStore()

        retriever = ParentDocumentRetriever(
            vectorstore=vector_store,
            docstore=store,
            child_splitter=child_splitter,
            parent_splitter=parent_splitter,
        )
        retriever.add_documents(documents)

        with self._lock:
            self._retriever = retriever

        return len(documents)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        if self._retriever is None:
            raise RuntimeError("Retriever is not initialized")

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        docs = self._retriever.invoke(question)
        context = "\n\n".join(doc.page_content for doc in docs)

        answer = llm.invoke(
            (
                "You are a hierarchical retriever assistant. Answer based on parent-child retrieved FAQ context.\n\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(answer=str(answer), chunksUsed=len(docs), strategy="parent-child-retriever")


pipeline = HierarchicalPipeline()
