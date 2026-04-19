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

        query = f"{question} {image_description}".strip() if image_description else question
        docs = vector_store.similarity_search(query, k=4)
        context = "\n\n".join(doc.page_content for doc in docs)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        prompt = (
            "You are a multimodal-style support assistant. Use FAQ context and optional image hints.\n\n"
            f"Question: {question}\n\n"
            f"Image Signals: {image_description or 'No image context provided'}\n\n"
            f"FAQ Context:\n{context}"
        )

        answer = llm.invoke(prompt).content
        return VisionRagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-direct+multimodal-context",
            orchestrationStrategy="langchain-multimodal-quickstart",
        )


pipeline = MultimodalPipeline()
