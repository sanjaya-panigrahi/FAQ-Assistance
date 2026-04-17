import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    app_port: int = int(os.getenv("APP_PORT", "8284"))
    faq_source_file: str = os.getenv("FAQ_SOURCE_FILE", "/opt/data/mytechstore-faq.md")
    openai_chat_model: str = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    openai_embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")


settings = Settings()
