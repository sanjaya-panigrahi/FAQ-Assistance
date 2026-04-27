"""Analytics MCP Server.

Exposes RAG analytics operations as MCP tools and resources.
Tools: log_rag_event, get_dashboard, get_score_distribution, score_with_llm
Resources: analytics://summary
"""

import json
import logging
import os
import uuid
from datetime import datetime, timedelta

from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

ANALYTICS_DB_HOST = os.getenv("ANALYTICS_DB_HOST", "analytics-mysql")
ANALYTICS_DB_PORT = int(os.getenv("ANALYTICS_DB_PORT", "3306"))
ANALYTICS_DB_NAME = os.getenv("ANALYTICS_DB_NAME", "rag_analytics")
ANALYTICS_DB_USER = os.getenv("ANALYTICS_DB_USER", "analytics")
ANALYTICS_DB_PASSWORD = os.getenv("ANALYTICS_DB_PASSWORD", "analyticspass")

mcp = FastMCP(
    "Analytics MCP Server",
    description="RAG query analytics, scoring, and dashboard data",
)

_engine = None
_SessionLocal = None


def _init_db():
    global _engine, _SessionLocal
    if _engine is None:
        from sqlalchemy import create_engine
        from sqlalchemy.orm import sessionmaker

        db_url = (
            f"mysql+pymysql://{ANALYTICS_DB_USER}:{ANALYTICS_DB_PASSWORD}@"
            f"{ANALYTICS_DB_HOST}:{ANALYTICS_DB_PORT}/{ANALYTICS_DB_NAME}?charset=utf8mb4"
        )
        _engine = create_engine(db_url, pool_pre_ping=True, pool_size=5)
        _SessionLocal = sessionmaker(bind=_engine)
    return _engine, _SessionLocal


def _get_session():
    _, SessionLocal = _init_db()
    return SessionLocal()


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def log_rag_event(
    query: str,
    response: str,
    customer: str,
    rag_pattern: str,
    framework: str,
    strategy: str = "",
    status: str = "success",
    latency_ms: int = 0,
    context_docs: str | None = None,
) -> dict:
    """Log a RAG query event for analytics tracking.

    Args:
        query: The user's question.
        response: The generated answer.
        customer: Customer/tenant identifier.
        rag_pattern: RAG pattern used (retrieval, agentic, corrective, graph, hierarchical, multimodal).
        framework: Framework used (langchain, langgraph, spring-ai).
        strategy: Pipeline strategy description.
        status: Event status ('success' or 'error').
        latency_ms: Total latency in milliseconds.
        context_docs: Retrieved context documents (for LLM scoring).

    Returns:
        Confirmation with the event ID.
    """
    from sqlalchemy import text

    session = _get_session()
    try:
        request_id = str(uuid.uuid4())

        # Compute heuristic sub-scores
        retrieval_quality = min(1.0, len(context_docs or "") / 500) if context_docs else 0.3
        grounded_correctness = 0.7 if response and "do not know" not in response.lower() else 0.3
        safety = 1.0
        latency_efficiency = max(0.0, 1.0 - (latency_ms / 10000))
        effective_score = (
            0.35 * retrieval_quality
            + 0.30 * grounded_correctness
            + 0.15 * safety
            + 0.20 * latency_efficiency
        )

        session.execute(
            text("""
                INSERT INTO query_events
                (request_id, query_text, response_text, customer_id, rag_pattern,
                 framework, strategy, status, latency_ms, retrieval_quality,
                 grounded_correctness, safety, latency_efficiency,
                 effective_rag_score, context_docs, llm_scored, created_at)
                VALUES
                (:request_id, :query, :response, :customer, :rag_pattern,
                 :framework, :strategy, :status, :latency_ms, :rq,
                 :gc, :safety, :le, :score, :ctx, 0, NOW())
            """),
            {
                "request_id": request_id, "query": query, "response": response,
                "customer": customer, "rag_pattern": rag_pattern,
                "framework": framework, "strategy": strategy, "status": status,
                "latency_ms": max(0, latency_ms), "rq": round(retrieval_quality, 4),
                "gc": round(grounded_correctness, 4), "safety": round(safety, 4),
                "le": round(latency_efficiency, 4), "score": round(effective_score, 4),
                "ctx": context_docs,
            },
        )
        session.commit()
        return {"status": "ok", "request_id": request_id, "effective_score": round(effective_score, 4)}
    except Exception as exc:
        session.rollback()
        return {"error": str(exc)}
    finally:
        session.close()


@mcp.tool()
def get_dashboard(
    rag_pattern: str | None = None,
    customer: str | None = None,
    limit: int = 30,
) -> dict:
    """Get the RAG analytics dashboard data.

    Args:
        rag_pattern: Optional filter by RAG pattern.
        customer: Optional filter by customer/tenant.
        limit: Maximum number of recent runs to return (5-200).

    Returns:
        Dashboard data with leaderboard and recent runs.
    """
    from sqlalchemy import text

    limit = max(5, min(200, limit))
    session = _get_session()
    try:
        # Leaderboard query
        where_clauses = ["created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)"]
        params = {"limit": limit}
        if rag_pattern:
            where_clauses.append("rag_pattern = :rag_pattern")
            params["rag_pattern"] = rag_pattern
        if customer:
            where_clauses.append("customer_id = :customer")
            params["customer"] = customer

        where_sql = " AND ".join(where_clauses)

        leaderboard_rows = session.execute(
            text(f"""
                SELECT framework, rag_pattern,
                       COUNT(*) AS total_runs,
                       SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) / COUNT(*) AS success_rate,
                       AVG(latency_ms) AS avg_latency_ms,
                       AVG(effective_rag_score) AS avg_score
                FROM query_events
                WHERE {where_sql}
                GROUP BY framework, rag_pattern
                ORDER BY avg_score DESC, avg_latency_ms ASC
                LIMIT 12
            """),
            params,
        ).fetchall()

        leaderboard = [
            {
                "framework": row[0],
                "ragPattern": row[1],
                "totalRuns": int(row[2]),
                "successRate": round(float(row[3] or 0), 4),
                "avgLatencyMs": round(float(row[4] or 0), 2),
                "avgEffectiveRagScore": round(float(row[5] or 0), 4),
            }
            for row in leaderboard_rows
        ]

        # Recent runs
        recent_rows = session.execute(
            text(f"""
                SELECT created_at, framework, rag_pattern, customer_id, query_text,
                       status, latency_ms, effective_rag_score, strategy, llm_scored
                FROM query_events
                WHERE {where_sql}
                ORDER BY created_at DESC
                LIMIT :limit
            """),
            params,
        ).fetchall()

        recent = [
            {
                "createdAt": row[0].isoformat() if row[0] else None,
                "framework": row[1],
                "ragPattern": row[2],
                "customer": row[3],
                "query": row[4],
                "status": row[5],
                "latencyMs": row[6],
                "effectiveRagScore": round(float(row[7] or 0), 4),
                "strategy": row[8],
                "llmScored": bool(row[9]),
            }
            for row in recent_rows
        ]

        return {"leaderboard": leaderboard, "recent": recent}
    except Exception as exc:
        return {"error": str(exc)}
    finally:
        session.close()


@mcp.tool()
def get_score_distribution(
    rag_pattern: str | None = None,
    customer: str | None = None,
    days: int = 7,
) -> dict:
    """Get score distribution histogram for RAG events.

    Args:
        rag_pattern: Optional filter by RAG pattern.
        customer: Optional filter by customer/tenant.
        days: Number of days to look back (1-90).

    Returns:
        Score distribution buckets and sub-score breakdown by framework.
    """
    from sqlalchemy import text

    days = max(1, min(90, days))
    session = _get_session()
    try:
        where_clauses = [f"created_at >= DATE_SUB(NOW(), INTERVAL {days} DAY)"]
        params = {}
        if rag_pattern:
            where_clauses.append("rag_pattern = :rag_pattern")
            params["rag_pattern"] = rag_pattern
        if customer:
            where_clauses.append("customer_id = :customer")
            params["customer"] = customer

        where_sql = " AND ".join(where_clauses)

        dist_rows = session.execute(
            text(f"""
                SELECT FLOOR(effective_rag_score * 10) / 10 AS bucket, COUNT(*) AS cnt
                FROM query_events
                WHERE {where_sql}
                GROUP BY bucket
                ORDER BY bucket
            """),
            params,
        ).fetchall()

        distribution = [
            {"bucket": f"{float(row[0]):.1f}-{float(row[0])+0.1:.1f}", "count": int(row[1])}
            for row in dist_rows
        ]

        return {"distribution": distribution, "days": days}
    except Exception as exc:
        return {"error": str(exc)}
    finally:
        session.close()


# ─── Resources ───────────────────────────────────────────────────────────────


@mcp.resource("analytics://summary")
def get_summary_resource() -> str:
    """Get a summary of RAG analytics data."""
    from sqlalchemy import text

    session = _get_session()
    try:
        row = session.execute(
            text("""
                SELECT COUNT(*) AS total_events,
                       AVG(effective_rag_score) AS avg_score,
                       AVG(latency_ms) AS avg_latency,
                       SUM(CASE WHEN status='success' THEN 1 ELSE 0 END)/COUNT(*) AS success_rate
                FROM query_events
                WHERE created_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
            """)
        ).fetchone()
        summary = {
            "period": "last_7_days",
            "total_events": int(row[0] or 0),
            "avg_effective_rag_score": round(float(row[1] or 0), 4),
            "avg_latency_ms": round(float(row[2] or 0), 2),
            "success_rate": round(float(row[3] or 0), 4),
        }
    except Exception as exc:
        summary = {"error": str(exc)}
    finally:
        session.close()
    return json.dumps(summary, indent=2)


@mcp.tool()
def health_check() -> dict:
    """Check Analytics database connectivity.

    Returns:
        Health status with event count.
    """
    from sqlalchemy import text

    try:
        session = _get_session()
        count = session.execute(text("SELECT COUNT(*) FROM query_events")).scalar()
        session.close()
        return {"status": "UP", "backend": "analytics-mysql", "total_events": count}
    except Exception as exc:
        return {"status": "DOWN", "backend": "analytics-mysql", "error": str(exc)}


if __name__ == "__main__":
    mcp.run(transport="sse")
