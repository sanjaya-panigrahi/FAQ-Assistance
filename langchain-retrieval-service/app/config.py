import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    openai_chat_model: str = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
    openai_embedding_model: str = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
    chroma_host: str = os.getenv("CHROMA_HOST", "chroma-faq")
    chroma_port: int = int(os.getenv("CHROMA_PORT", "8000"))
    chroma_collection_prefix: str = os.getenv("APP_CHROMA_COLLECTION_NAME_PREFIX", "faq_")
    redis_host: str = os.getenv("REDIS_HOST", "redis-cache")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))
    redis_ttl_seconds: int = int(os.getenv("RETRIEVAL_CACHE_TTL_SECONDS", "400"))

    # --- Enhanced pipeline settings ---
    cohere_api_key: str = os.getenv("COHERE_API_KEY", "")

    # Feature flags
    enable_multi_query: bool = os.getenv("ENABLE_MULTI_QUERY", "true").lower() == "true"
    enable_ensemble_bm25: bool = os.getenv("ENABLE_ENSEMBLE_BM25", "true").lower() == "true"
    enable_cohere_rerank: bool = os.getenv("ENABLE_COHERE_RERANK", "true").lower() == "true"
    enable_adaptive_k: bool = os.getenv("ENABLE_ADAPTIVE_K", "true").lower() == "true"

    # MultiQueryRetriever
    multi_query_count: int = int(os.getenv("MULTI_QUERY_COUNT", "3"))

    # EnsembleRetriever + BM25
    ensemble_vector_weight: float = float(os.getenv("ENSEMBLE_VECTOR_WEIGHT", "0.6"))
    ensemble_bm25_weight: float = float(os.getenv("ENSEMBLE_BM25_WEIGHT", "0.4"))
    ensemble_candidate_k: int = int(os.getenv("ENSEMBLE_CANDIDATE_K", "20"))

    # CohereRerank
    cohere_rerank_model: str = os.getenv("COHERE_RERANK_MODEL", "rerank-v3.5")
    cohere_rerank_top_n: int = int(os.getenv("COHERE_RERANK_TOP_N", "6"))

    # Adaptive k
    adaptive_k_threshold: float = float(os.getenv("ADAPTIVE_K_THRESHOLD", "0.75"))
    adaptive_k_high: int = int(os.getenv("ADAPTIVE_K_HIGH", "4"))
    adaptive_k_low: int = int(os.getenv("ADAPTIVE_K_LOW", "10"))


settings = Settings()
