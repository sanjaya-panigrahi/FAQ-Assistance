"""Shared configuration for all MCP servers."""

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class McpConfig:
    """Shared configuration loaded from environment variables."""

    # ChromaDB
    chroma_host: str = os.getenv("CHROMA_HOST", "chroma-faq")
    chroma_port: int = int(os.getenv("CHROMA_PORT", "8000"))
    chroma_collection_prefix: str = os.getenv("APP_CHROMA_COLLECTION_NAME_PREFIX", "faq_")

    # Neo4j
    neo4j_uri: str = os.getenv("NEO4J_URI", "bolt://neo4j-unified:7687")
    neo4j_username: str = os.getenv("NEO4J_USERNAME", "neo4j")
    neo4j_password: str = os.getenv("NEO4J_PASSWORD", "neo4jpass")

    # Redis
    redis_host: str = os.getenv("REDIS_HOST", "redis-cache")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    redis_ttl_seconds: int = int(os.getenv("REDIS_TTL_SECONDS", "86400"))

    # OpenAI
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    openai_chat_model: str = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    openai_embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")

    # Tavily
    tavily_api_key: str = os.getenv("TAVILY_API_KEY", "")

    # Analytics
    analytics_db_host: str = os.getenv("ANALYTICS_DB_HOST", "analytics-mysql")
    analytics_db_port: int = int(os.getenv("ANALYTICS_DB_PORT", "3306"))
    analytics_db_name: str = os.getenv("ANALYTICS_DB_NAME", "rag_analytics")
    analytics_db_user: str = os.getenv("ANALYTICS_DB_USER", "analytics")
    analytics_db_password: str = os.getenv("ANALYTICS_DB_PASSWORD", "analyticspass")

    # MCP Auth
    mcp_api_key: str = os.getenv("MCP_API_KEY", "")

    # Observability
    zipkin_host: str = os.getenv("ZIPKIN_HOST", "zipkin")
    zipkin_port: int = int(os.getenv("ZIPKIN_PORT", "9411"))
    log_level: str = os.getenv("LOG_LEVEL", "INFO")


config = McpConfig()
