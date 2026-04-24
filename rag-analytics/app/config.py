import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    db_host: str = os.getenv("ANALYTICS_DB_HOST", "analytics-mysql")
    db_port: int = int(os.getenv("ANALYTICS_DB_PORT", "3306"))
    db_name: str = os.getenv("ANALYTICS_DB_NAME", "rag_analytics")
    db_user: str = os.getenv("ANALYTICS_DB_USER", "analytics")
    db_password: str = os.getenv("ANALYTICS_DB_PASSWORD", "analyticspass")

    redis_host: str = os.getenv("REDIS_HOST", "redis-cache")
    redis_port: int = int(os.getenv("REDIS_PORT", "6379"))

    llm_scoring_enabled: bool = os.getenv("LLM_SCORING_ENABLED", "true").lower() in ("1", "true", "yes")
    llm_judge_model: str = os.getenv("LLM_JUDGE_MODEL", "gpt-4o-mini")
    llm_judge_timeout: int = int(os.getenv("LLM_JUDGE_TIMEOUT_SECONDS", "30"))

    @property
    def database_url(self) -> str:
        return (
            f"mysql+pymysql://{self.db_user}:{self.db_password}@"
            f"{self.db_host}:{self.db_port}/{self.db_name}?charset=utf8mb4"
        )

    @property
    def celery_broker_url(self) -> str:
        return f"redis://{self.redis_host}:{self.redis_port}/6"

    @property
    def celery_result_backend(self) -> str:
        return f"redis://{self.redis_host}:{self.redis_port}/7"


settings = Settings()
