import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    app_port: int = int(os.getenv("APP_PORT", "9191"))
    db_host: str = os.getenv("ANALYTICS_DB_HOST", "analytics-mysql")
    db_port: int = int(os.getenv("ANALYTICS_DB_PORT", "3306"))
    db_name: str = os.getenv("ANALYTICS_DB_NAME", "rag_analytics")
    db_user: str = os.getenv("ANALYTICS_DB_USER", "analytics")
    db_password: str = os.getenv("ANALYTICS_DB_PASSWORD", "analyticspass")

    @property
    def database_url(self) -> str:
        return (
            f"mysql+pymysql://{self.db_user}:{self.db_password}@"
            f"{self.db_host}:{self.db_port}/{self.db_name}?charset=utf8mb4"
        )


settings = Settings()
