from datetime import datetime, timedelta
import json
import logging

from sqlalchemy import case, func, select

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from .config import settings
from .db import SessionLocal
from .models import QueryEvent
from .schemas import (
    DashboardResponse,
    LeaderboardItem,
    QueryMetricBatchIn,
    QueryMetricIn,
    RecentRun,
    ScoreDistributionBucket,
    ScoreDistributionResponse,
    SubScoreBreakdown,
)
from .scoring import derive_effective_score, derive_phase_timings, derive_subscores

logger = logging.getLogger(__name__)


router = APIRouter()


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


@router.get("/actuator/health")
def health(db: Session = Depends(get_db)) -> dict:
    try:
        total = db.scalar(select(func.count(QueryEvent.id))) or 0
        return {"status": "UP", "events": int(total)}
    except Exception as exc:  # pragma: no cover
        return {"status": "DEGRADED", "error": str(exc)}


@router.post("/api/analytics/events")
def ingest_events(payload: QueryMetricBatchIn, db: Session = Depends(get_db)) -> dict:
    if not payload.events:
        raise HTTPException(status_code=400, detail="events must not be empty")

    inserted = 0
    for event in payload.events:
        inserted += _insert_event(db, event)

    db.commit()
    return {"status": "ok", "inserted": inserted}


@router.get("/api/analytics/dashboard", response_model=DashboardResponse)
def get_dashboard(
    ragPattern: str | None = Query(default=None),
    customer: str | None = Query(default=None),
    limit: int = Query(default=30, ge=5, le=200),
    db: Session = Depends(get_db),
) -> DashboardResponse:
    filters = []
    if ragPattern:
        filters.append(QueryEvent.rag_pattern == ragPattern)
    if customer:
        filters.append(QueryEvent.customer_id == customer)

    success_rate_expr = (
        func.sum(case((QueryEvent.status == "success", 1), else_=0))
        / func.count(QueryEvent.id)
    )

    # Add time window filter (last 7 days) to improve query performance
    time_window = datetime.utcnow() - timedelta(days=7)
    leaderboard_stmt = (
        select(
            QueryEvent.framework,
            QueryEvent.rag_pattern,
            func.count(QueryEvent.id).label("total_runs"),
            success_rate_expr.label("success_rate"),
            func.avg(QueryEvent.latency_ms).label("avg_latency_ms"),
            func.avg(QueryEvent.effective_rag_score).label("avg_effective_rag_score"),
        )
        .where(QueryEvent.created_at >= time_window, *filters)
        .group_by(QueryEvent.framework, QueryEvent.rag_pattern)
        .having(func.count(QueryEvent.id) >= 2)  # Only include patterns with 2+ runs
        .order_by(func.avg(QueryEvent.effective_rag_score).desc(), func.avg(QueryEvent.latency_ms).asc())
        .limit(12)
    )

    leaderboard_rows = db.execute(leaderboard_stmt).all()
    leaderboard = [
        LeaderboardItem(
            framework=row.framework,
            ragPattern=row.rag_pattern,
            totalRuns=int(row.total_runs or 0),
            successRate=round(float(row.success_rate or 0), 4),
            avgLatencyMs=round(float(row.avg_latency_ms or 0), 2),
            avgEffectiveRagScore=round(float(row.avg_effective_rag_score or 0), 4),
        )
        for row in leaderboard_rows
    ]

    recent_stmt = (
        select(QueryEvent)
        .where(*filters)
        .order_by(QueryEvent.created_at.desc())
        .limit(limit)
    )
    recent_rows = db.execute(recent_stmt).scalars().all()
    recent = [
        RecentRun(
            createdAt=row.created_at,
            framework=row.framework,
            ragPattern=row.rag_pattern,
            customer=row.customer_id,
            query=row.query_text,
            status=row.status,
            latencyMs=row.latency_ms,
            effectiveRagScore=round(float(row.effective_rag_score), 4),
            strategy=row.strategy,
            llmScored=bool(row.llm_scored),
            retrievalQuality=round(float(row.retrieval_quality), 4) if row.retrieval_quality is not None else None,
            groundedCorrectness=round(float(row.grounded_correctness), 4) if row.grounded_correctness is not None else None,
            safety=round(float(row.safety), 4) if row.safety is not None else None,
            latencyEfficiency=round(float(row.latency_efficiency), 4) if row.latency_efficiency is not None else None,
            judgeExplanations=json.loads(row.judge_explanations) if row.judge_explanations else None,
        )
        for row in recent_rows
    ]

    return DashboardResponse(leaderboard=leaderboard, recent=recent)


@router.get("/api/analytics/score-distribution", response_model=ScoreDistributionResponse)
def get_score_distribution(
    ragPattern: str | None = Query(default=None),
    customer: str | None = Query(default=None),
    days: int = Query(default=7, ge=1, le=90),
    db: Session = Depends(get_db),
) -> ScoreDistributionResponse:
    """Score distribution histogram and per-subscore breakdown."""
    filters = [QueryEvent.created_at >= datetime.utcnow() - timedelta(days=days)]
    if ragPattern:
        filters.append(QueryEvent.rag_pattern == ragPattern)
    if customer:
        filters.append(QueryEvent.customer_id == customer)

    # Histogram: bucket scores into 0.1 ranges
    bucket_expr = func.floor(QueryEvent.effective_rag_score * 10) / 10
    dist_stmt = (
        select(
            bucket_expr.label("bucket"),
            func.count(QueryEvent.id).label("count"),
        )
        .where(*filters)
        .group_by(bucket_expr)
        .order_by(bucket_expr)
    )
    dist_rows = db.execute(dist_stmt).all()
    distribution = [
        ScoreDistributionBucket(
            bucket=f"{float(row.bucket):.1f}-{float(row.bucket)+0.1:.1f}",
            count=int(row.count),
        )
        for row in dist_rows
    ]

    # Sub-score breakdown by framework × pattern
    llm_scored_count = func.sum(case((QueryEvent.llm_scored == True, 1), else_=0))
    heuristic_count = func.sum(case((QueryEvent.llm_scored == False, 1), else_=0))
    breakdown_stmt = (
        select(
            QueryEvent.framework,
            QueryEvent.rag_pattern,
            func.avg(QueryEvent.retrieval_quality).label("avg_rq"),
            func.avg(QueryEvent.grounded_correctness).label("avg_gc"),
            func.avg(QueryEvent.safety).label("avg_s"),
            func.avg(QueryEvent.latency_efficiency).label("avg_le"),
            func.avg(QueryEvent.effective_rag_score).label("avg_score"),
            llm_scored_count.label("llm_count"),
            heuristic_count.label("heuristic_count"),
        )
        .where(*filters)
        .group_by(QueryEvent.framework, QueryEvent.rag_pattern)
        .having(func.count(QueryEvent.id) >= 1)
        .order_by(func.avg(QueryEvent.effective_rag_score).desc())
    )
    breakdown_rows = db.execute(breakdown_stmt).all()
    subscoreBreakdown = [
        SubScoreBreakdown(
            framework=row.framework,
            ragPattern=row.rag_pattern,
            avgRetrievalQuality=round(float(row.avg_rq or 0), 4),
            avgGroundedCorrectness=round(float(row.avg_gc or 0), 4),
            avgSafety=round(float(row.avg_s or 0), 4),
            avgLatencyEfficiency=round(float(row.avg_le or 0), 4),
            avgEffectiveRagScore=round(float(row.avg_score or 0), 4),
            llmScoredCount=int(row.llm_count or 0),
            heuristicCount=int(row.heuristic_count or 0),
        )
        for row in breakdown_rows
    ]

    return ScoreDistributionResponse(
        distribution=distribution,
        subscoreBreakdown=subscoreBreakdown,
    )


def _insert_event(db: Session, event: QueryMetricIn) -> int:
    retrieval_quality, grounded_correctness, safety, latency_efficiency = derive_subscores(event)
    score = derive_effective_score(retrieval_quality, grounded_correctness, safety, latency_efficiency)
    query_parse_ms, retrieval_ms, rerank_ms, generation_ms, post_checks_ms = derive_phase_timings(event)

    row = QueryEvent(
        request_id=event.requestId,
        mode=event.mode,
        query_text=event.query,
        response_text=event.response,
        customer_id=event.customer,
        rag_pattern=event.ragPattern,
        framework=event.framework,
        strategy=event.strategy,
        status=event.status,
        latency_ms=event.latencyMs,
        retrieval_quality=retrieval_quality,
        grounded_correctness=grounded_correctness,
        safety=safety,
        latency_efficiency=latency_efficiency,
        effective_rag_score=score,
        query_parse_ms=query_parse_ms,
        retrieval_ms=retrieval_ms,
        rerank_ms=rerank_ms,
        generation_ms=generation_ms,
        post_checks_ms=post_checks_ms,
        context_docs=event.contextDocs,
        llm_scored=False,
    )
    db.add(row)
    db.flush()  # get row.id for the Celery task

    # Dispatch async LLM scoring if context_docs was provided
    if settings.llm_scoring_enabled and event.contextDocs:
        try:
            from .worker import llm_score_event
            llm_score_event.delay(row.id)
        except Exception:
            logger.warning("Failed to enqueue LLM scoring for event %d", row.id, exc_info=True)

    return 1
