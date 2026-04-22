import chromadb
import sys
from pathlib import Path

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse

# Add shared-patterns to path for pattern registry import
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "shared-patterns"))
from faq_pattern_registry import get_registry


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class HierarchicalPipeline:
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
        selected_section = self._select_section(question)
        query = f"{question} {selected_section}".strip()

        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )
        docs = vector_store.similarity_search(query, k=4)
        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langgraph-hierarchy-multistep",
                selectedSection=selected_section,
            )
        context = "\n\n".join(doc.page_content for doc in docs)

        
        # Try structured extraction using pattern registry
        registry = get_registry()
        structured_answer = registry.extract_faq_answer(question, combined_context)
        if structured_answer and "No structured answer" not in structured_answer:
            return RagResponse(
                answer=structured_answer,
                chunksUsed=len(docs),
                strategy="pattern-registry+structured-extraction",
                orchestrationStrategy="langgraph-hierarchy-multistep",
                selectedSection=section,
            )
                "If context provides a general policy and no product-specific exception, use the general policy. "
                "Do not invent policy windows or generic caveats unless they appear in context.\n\n"
                f"Section selected: {selected_section}\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-hierarchy",
            orchestrationStrategy="langgraph-hierarchy-multistep",
            selectedSection=selected_section,
        )

    def _select_section(self, question: str) -> str:
        q = question.lower()
        if any(t in q for t in ["return", "refund", "replacement", "exchange"]):
            return "Returns and Refunds"
        if any(t in q for t in ["shipping", "delivery", "track", "dispatch"]):
            return "Shipping and Delivery"
        if any(t in q for t in ["warranty", "guarantee", "repair"]):
            return "Warranty and Support"
        return "General FAQ"

    @staticmethod
    def _extract_deterministic_return_policy(question: str, context: str) -> str | None:
        q = (question or "").lower()
        if "return" not in q or "policy" not in q:
            return "Returns and Refunds"
