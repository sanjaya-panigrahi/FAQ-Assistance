import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import VisionRagResponse


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
        docs = vector_store.similarity_search(query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a multimodal FAQ assistant. Use image hints only when present; otherwise rely on FAQ context.\n\n"
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


pipeline = MultimodalPipeline()
