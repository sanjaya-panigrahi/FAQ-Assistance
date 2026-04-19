import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    app_port: int = int(os.getenv("APP_PORT", "8283"))
    faq_source_file: str = os.getenv("FAQ_SOURCE_FILE", "/opt/data/mytechstore-faq.md")
    openai_chat_model: str = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    openai_embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    chroma_host: str = os.getenv("CHROMA_HOST", "chroma-faq")
    chroma_port: int = int(os.getenv("CHROMA_PORT", "8000"))
    chroma_collection_prefix: str = os.getenv("APP_CHROMA_COLLECTION_NAME_PREFIX", "faq_")


settings = Settings()
