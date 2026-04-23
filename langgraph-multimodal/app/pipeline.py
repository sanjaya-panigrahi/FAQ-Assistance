import chromadb
import sys
from pathlib import Path

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import VisionRagResponse

# Add shared-patterns to path for pattern registry import
sys.path.insert(0, str(Path(__file__).parent.parent.parent / "shared-patterns"))
from faq_pattern_registry import get_registry


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class MultimodalPipeline:
    def health(self) -> dict:
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, image_description: str, customer_id: str | None = None) -> VisionRagResponse:
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )

        use_image_branch = bool((image_description or "").strip())
        query = f"{question} {image_description}".strip() if use_image_branch else question
        docs = vector_store.similarity_search(query, k=6)
        if not docs:
            return VisionRagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langgraph-multimodal-branching",
            )
        context = "\n\n".join(doc.page_content for doc in docs)

        
        # Try structured extraction using pattern registry
        registry = get_registry()
        structured_answer = registry.extract_faq_answer(question, combined_context)
        if structured_answer and "No structured answer" not in structured_answer:
            return VisionRagResponse(
                answer=structured_answer,
                chunksUsed=len(docs),
                strategy="pattern-registry+structured-extraction",
                orchestrationStrategy="langgraph-multimodal-branching",
            )
            (
                "You are a multimodal FAQ assistant. Use image hints only when present; otherwise rely on FAQ context. "
                "If the context contains a general policy (e.g. return policy, warranty), apply it directly to the specific product the user asks about. "
                "Do not say the information is missing if a general policy covers it. "
                "Do not invent facts or add caveats not present in the context.\n\n"
                f"Image Branch: {use_image_branch}\n"
                f"Question: {question}\n"
                f"Image Signals: {image_description or 'none'}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return VisionRagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+langgraph-branching",
            orchestrationStrategy="langgraph-multimodal-branching",
        )

    @staticmethod
    def _extract_deterministic_return_policy(question: str, context: str) -> str | None:
        q = (question or "").lower()
        if "return" not in q or "policy" not in q:
        return VisionRagResponse(
