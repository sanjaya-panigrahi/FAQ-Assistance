import threading
from collections import OrderedDict

from langchain.storage import InMemoryStore
from langchain_core.documents import Document
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
        self._faq_entries: list[Document] = []
        self._section_headers: list[str] = []

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
            self._faq_entries = documents
            self._section_headers = self._collect_section_headers(documents)

        return len(documents)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        if self._retriever is None:
            raise RuntimeError("Retriever is not initialized")

        selected_section = self._select_section(question)
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        docs = self._collect_documents(question, selected_section)
        context = "\n\n".join(doc.page_content for doc in docs)

        answer = llm.invoke(
            (
                "You are hierarchical RAG assistant for MyTechStore. "
                f"Section selected: {selected_section}. "
                "Answer only from context. If the context provides a general policy and no product-specific exception, use the general policy. "
                "Keep the answer concise and do not add unrelated policy notes or website references.\n\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="parent-child-retriever",
            selectedSection=selected_section,
        )

    def _collect_documents(self, question: str, selected_section: str) -> list[Document]:
        if self._retriever is None:
            raise RuntimeError("Retriever is not initialized")

        query = f"{question} {selected_section}".strip()
        retrieved_docs = self._retriever.invoke(query)
        lexical_docs = self._lexical_hits(query)

        merged: OrderedDict[str, Document] = OrderedDict()
        for doc in self._rank_documents(retrieved_docs + lexical_docs, question, selected_section):
            merged.setdefault(doc.page_content, doc)

        return list(merged.values())[:4]

    def _lexical_hits(self, query: str) -> list[Document]:
        ranked = sorted(
            self._faq_entries,
            key=lambda doc: self._document_score(query, doc, None),
            reverse=True,
        )
        return [doc for doc in ranked if self._document_score(query, doc, None) > 0][:4]

    def _rank_documents(self, docs: list[Document], question: str, selected_section: str) -> list[Document]:
        return sorted(
            docs,
            key=lambda doc: self._document_score(question, doc, selected_section),
            reverse=True,
        )

    def _document_score(self, question: str, doc: Document, selected_section: str | None) -> int:
        text = self._normalize(doc.page_content)
        section = str(doc.metadata.get("section", ""))
        tokens = [token for token in self._normalize(question).split() if token]

        score = 0
        for token in tokens:
            if token in text:
                score += 2
        if "return" in tokens and "policy" in tokens and "return policy" in text:
            score += 5
        if selected_section and section == selected_section:
            score += 8
        return score

    def _select_section(self, question: str) -> str:
        normalized = self._normalize(question)
        section_preferences = [
            (["return", "refund", "replace", "defect"], ["Returns, Refunds, and Replacements", "Returns and Warranty"]),
            (["warranty", "damage", "protection", "repair"], ["Warranty and Protection Plans", "Returns and Warranty"]),
            (["shipping", "delivery", "track", "pickup", "order status"], ["Shipping and Delivery Details", "Shipping and Delivery"]),
            (["order", "checkout", "cancel", "invoice", "payment"], ["Orders and Checkout", "Pricing and Payments"]),
            (["branch", "store", "support", "contact"], ["In-Store Services and Branch Support", "Branches and Support", "Support and Policies"]),
        ]

        for keywords, candidates in section_preferences:
            if any(keyword in normalized for keyword in keywords):
                for candidate in candidates:
                    if candidate in self._section_headers:
                        return candidate

        for section in self._section_headers:
            first_word = section.split()[0].lower()
            if first_word and first_word in normalized:
                return section

        return "General FAQ"

    def _collect_section_headers(self, docs: list[Document]) -> list[str]:
        headers: list[str] = []
        for doc in docs:
            section = str(doc.metadata.get("section", "")).strip()
            if section and section not in headers:
                headers.append(section)
        return headers

    def _normalize(self, value: str) -> str:
        return " ".join("".join(ch.lower() if ch.isalnum() else " " for ch in value).split())


pipeline = HierarchicalPipeline()
