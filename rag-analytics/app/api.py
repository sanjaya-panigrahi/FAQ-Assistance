from datetime import datetime, timedelta
from sqlalchemy import case, func, select

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.orm import Session

from .db import SessionLocal
from .models import QueryEvent
from .schemas import DashboardResponse, LeaderboardItem, QueryMetricBatchIn, QueryMetricIn, RecentRun
from .scoring import derive_effective_score, derive_phase_timings, derive_subscores


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
        )
        for row in recent_rows
    ]

    return DashboardResponse(leaderboard=leaderboard, recent=recent)


def _insert_event(db: Session, event: QueryMetricIn) -> int:
    retrieval_quality, grounded_correctness, safety, latency_efficiency = derive_subscores(event)
    score = derive_effective_score(retrieval_quality, grounded_correctness, safety, latency_efficiency)
    query_parse_ms, retrieval_ms, rerank_ms, generation_ms, post_checks_ms = derive_phase_timings(event)

    db.add(
        QueryEvent(
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
        )
    )
    return 1
