import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    app_port: int = int(os.getenv("APP_PORT", "8282"))
    faq_source_file: str = os.getenv("FAQ_SOURCE_FILE", "/opt/data/mytechstore-faq.md")
    openai_chat_model: str = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    openai_embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    neo4j_uri: str = os.getenv("NEO4J_URI", "bolt://neo4j-langgraph:7687")
    neo4j_username: str = os.getenv("NEO4J_USERNAME", "neo4j")
    neo4j_password: str = os.getenv("NEO4J_PASSWORD", "neo4jpass")
    chroma_host: str = os.getenv("CHROMA_HOST", "chroma-faq")
    chroma_port: int = int(os.getenv("CHROMA_PORT", "8000"))
    chroma_collection_prefix: str = os.getenv("APP_CHROMA_COLLECTION_NAME_PREFIX", "faq_")


settings = Settings()
