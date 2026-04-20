from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String, Text
from sqlalchemy.orm import Mapped, mapped_column

from .db import Base


class QueryEvent(Base):
    __tablename__ = "query_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    request_id: Mapped[str] = mapped_column(String(64), index=True)
    mode: Mapped[str] = mapped_column(String(16), default="single")
    query_text: Mapped[str] = mapped_column(Text)
    response_text: Mapped[str] = mapped_column(Text)
    customer_id: Mapped[str] = mapped_column(String(128), index=True)
    rag_pattern: Mapped[str] = mapped_column(String(128), index=True)
    framework: Mapped[str] = mapped_column(String(64), index=True)
    strategy: Mapped[str] = mapped_column(String(255), default="")
    status: Mapped[str] = mapped_column(String(16), default="success")
    latency_ms: Mapped[int] = mapped_column(Integer)

    retrieval_quality: Mapped[float] = mapped_column(Float)
    grounded_correctness: Mapped[float] = mapped_column(Float)
    safety: Mapped[float] = mapped_column(Float)
    latency_efficiency: Mapped[float] = mapped_column(Float)
    effective_rag_score: Mapped[float] = mapped_column(Float, index=True)

    query_parse_ms: Mapped[int] = mapped_column(Integer, default=0)
    retrieval_ms: Mapped[int] = mapped_column(Integer, default=0)
    rerank_ms: Mapped[int] = mapped_column(Integer, default=0)
    generation_ms: Mapped[int] = mapped_column(Integer, default=0)
    post_checks_ms: Mapped[int] = mapped_column(Integer, default=0)

    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
