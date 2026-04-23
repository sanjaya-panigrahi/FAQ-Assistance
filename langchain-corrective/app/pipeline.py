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

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langchain-light-fallback",
            )

        context = "\n\n".join(doc.page_content for doc in docs)
        
        # Try structured extraction using pattern registry
        registry = get_registry()
        structured_answer = registry.extract_faq_answer(question, context)
        if structured_answer and "No structured answer" not in structured_answer:
            return RagResponse(
                answer=structured_answer,
                chunksUsed=len(docs),
                strategy="pattern-registry+structured-extraction",
                orchestrationStrategy="langchain-light-fallback",
            )

        answer = llm.invoke(
            (
                "You are a corrective RAG assistant. Answer using ONLY the FAQ context provided below. "
                "If the context contains a general policy (e.g. return policy, warranty), apply it directly to the specific product the user asks about. "
                "Do not say the information is missing if a general policy covers it. "
                "Do not invent facts or add caveats not present in the context.\n\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+light-fallback",
            orchestrationStrategy="langchain-light-fallback",
        )


pipeline = CorrectivePipeline()
