"""Celery application and async LLM-scoring worker task."""

import json
import logging

from celery import Celery

from .config import settings

logger = logging.getLogger(__name__)

celery_app = Celery(
    "rag_analytics",
    broker=settings.celery_broker_url,
    backend=settings.celery_result_backend,
)
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    task_soft_time_limit=60,
    task_time_limit=90,
    worker_max_tasks_per_child=200,
    task_acks_late=True,
    worker_prefetch_multiplier=1,
)


@celery_app.task(bind=True, max_retries=2, default_retry_delay=10)
def llm_score_event(self, event_id: int) -> dict:
    """Score a single QueryEvent with LLM judges, then update MySQL.

    Called asynchronously after an event is ingested so the ingest
    endpoint stays fast.
    """
    # Late imports to avoid circular deps and ensure DB is ready
    from .db import SessionLocal
    from .models import QueryEvent
    from .llm_scoring import evaluate
    from .scoring import clamp01, derive_effective_score

    db = SessionLocal()
    try:
        event: QueryEvent | None = db.get(QueryEvent, event_id)
        if event is None:
            logger.warning("Event %d not found – skipping LLM scoring", event_id)
            return {"status": "skipped", "reason": "not_found"}

        if event.llm_scored:
            return {"status": "skipped", "reason": "already_scored"}

        context_docs = event.context_docs or ""
        if not context_docs.strip():
            logger.info("Event %d has no context_docs – skipping LLM scoring", event_id)
            return {"status": "skipped", "reason": "no_context"}

        try:
            scores = evaluate(
                question=event.query_text,
                response_text=event.response_text,
                context_docs=context_docs,
            )
        except Exception as exc:
            logger.error("LLM scoring failed for event %d: %s", event_id, exc)
            raise self.retry(exc=exc)

        # Update the event with LLM-derived scores
        event.retrieval_quality = clamp01(scores.retrieval_quality.score)
        event.grounded_correctness = clamp01(scores.grounded_correctness.score)
        event.safety = clamp01(scores.safety.score)
        # latency_efficiency stays formula-based (no LLM needed)
        event.effective_rag_score = derive_effective_score(
            event.retrieval_quality,
            event.grounded_correctness,
            event.safety,
            event.latency_efficiency,
        )
        event.llm_scored = True
        event.judge_explanations = json.dumps({
            "retrieval_quality": scores.retrieval_quality.explanation,
            "grounded_correctness": scores.grounded_correctness.explanation,
            "safety": scores.safety.explanation,
        })

        db.commit()
        logger.info(
            "LLM scored event %d → effective=%.4f (rq=%.2f gc=%.2f s=%.2f)",
            event_id, event.effective_rag_score,
            event.retrieval_quality, event.grounded_correctness, event.safety,
        )
        return {
            "status": "scored",
            "event_id": event_id,
            "effective_rag_score": event.effective_rag_score,
        }
    finally:
        db.close()
