"""Configuration management using Pydantic Settings."""
import os
from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings from environment variables."""
    
    # Service
    service_name: str = "langchain-retrieval-service"
    service_version: str = "1.0.0"
    environment: str = os.getenv("ENVIRONMENT", "development")
    log_level: str = os.getenv("LOG_LEVEL", "INFO")
    debug: bool = environment == "development"
    
    # API
    api_host: str = "0.0.0.0"
    api_port: int = 8000
    api_workers: int = 4
    api_reload: bool = debug
    
    # JWT
    jwt_secret: str = os.getenv("JWT_SECRET", "")
    jwt_algorithm: str = "HS256"
    jwt_expiration_hours: int = int(os.getenv("JWT_EXPIRATION_HOURS", "1"))
    jwt_refresh_expiration_days: int = int(os.getenv("JWT_REFRESH_EXPIRATION_DAYS", "1"))
    
    # Security
    cors_origins: list[str] = ["http://localhost:3000", "http://localhost:5173"]
    cors_allow_credentials: bool = False
    cors_allow_methods: list[str] = ["GET", "POST", "OPTIONS"]
    cors_allow_headers: list[str] = ["Authorization", "Content-Type", "Idempotency-Key", "X-Request-ID"]
    
    # Database / Storage
    elasticsearch_host: str = os.getenv("ELASTICSEARCH_HOST", "localhost")
    elasticsearch_port: int = int(os.getenv("ELASTICSEARCH_PORT", "9200"))
    elasticsearch_index: str = "faq-documents"
    
    # Redis / Cache
    redis_host: str = os.getenv("REDIS_HOST", "localhost")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    redis_db: int = 0
    redis_ttl_seconds: int = 86400  # 24 hours
    cache_enabled: bool = True
    
    # Neo4j
    neo4j_host: str = os.getenv("NEO4J_HOST", "localhost")
    neo4j_port: int = int(os.getenv("NEO4J_PORT", "7687"))
    neo4j_user: str = os.getenv("NEO4J_USER", "neo4j")
    neo4j_password: str = os.getenv("NEO4J_PASSWORD", "")
    
    # LangChain
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    embeddings_model: str = "text-embedding-3-small"
    llm_model: str = "gpt-4"
    llm_temperature: float = 0.3
    
    # Monitoring
    metrics_enabled: bool = True
    metrics_port: int = 9090
    health_check_interval: int = 30
    
    # Timeout and limits
    query_timeout_seconds: int = 30
    max_context_length: int = 4000
    max_results: int = 10
    
    # Tracing / Telemetry
    tracing_enabled: bool = environment != "development"
    tracing_sample_rate: float = float(os.getenv("TRACING_SAMPLE_RATE", "1.0"))
    zipkin_host: str = os.getenv("ZIPKIN_HOST", "localhost")
    zipkin_port: int = int(os.getenv("ZIPKIN_PORT", "9411"))
    
    # Tenant isolation
    default_tenant_id: str = "smoke_tenant"
    enable_tenant_validation: bool = True

    @model_validator(mode="after")
    def validate_production_settings(self):
        if self.environment != "development":
            if len(self.jwt_secret) < 32:
                raise ValueError("JWT_SECRET must be at least 32 characters outside development")
            if not self.openai_api_key:
                raise ValueError("OPENAI_API_KEY must be configured outside development")
            if self.neo4j_host not in {"localhost", "127.0.0.1"} and not self.neo4j_password:
                raise ValueError("NEO4J_PASSWORD must be configured outside development")
        return self

    model_config = SettingsConfigDict(
        env_file=".env",
        case_sensitive=False
    )


def get_settings() -> Settings:
    """Get application settings."""
    return Settings()
