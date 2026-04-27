"""Cache MCP Server.

Exposes Redis cache operations as MCP tools and resources.
Tools: cache_get, cache_set, cache_delete, cache_invalidate_pattern
Resources: redis://stats
"""

import hashlib
import json
import logging
import os

import redis
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

REDIS_HOST = os.getenv("REDIS_HOST", "redis-cache")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
DEFAULT_TTL = int(os.getenv("REDIS_TTL_SECONDS", "86400"))

mcp = FastMCP(
    "Cache MCP Server",
    description="Redis cache operations for query response caching and embedding caching",
)

_pools: dict[int, redis.Redis] = {}


def _get_client(db: int = 0) -> redis.Redis:
    if db not in _pools:
        _pools[db] = redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            db=db,
            decode_responses=True,
        )
    return _pools[db]


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def cache_get(
    key: str,
    db: int = 0,
) -> dict:
    """Get a cached value by key from Redis.

    Args:
        key: The cache key to look up.
        db: Redis database number (0=responses, 1=embeddings).

    Returns:
        The cached value (as string or parsed JSON) or null if not found.
    """
    client = _get_client(db)
    try:
        value = client.get(key)
        if value is None:
            return {"key": key, "found": False, "value": None}
        # Try to parse as JSON
        try:
            parsed = json.loads(value)
            return {"key": key, "found": True, "value": parsed}
        except (json.JSONDecodeError, TypeError):
            return {"key": key, "found": True, "value": value}
    except Exception as exc:
        return {"error": str(exc), "key": key}


@mcp.tool()
def cache_set(
    key: str,
    value: str,
    ttl_seconds: int | None = None,
    db: int = 0,
) -> dict:
    """Store a value in Redis cache with optional TTL.

    Args:
        key: The cache key.
        value: The value to store (string or JSON string).
        ttl_seconds: Time-to-live in seconds. Uses default if not specified.
        db: Redis database number (0=responses, 1=embeddings).

    Returns:
        Confirmation of the cache set operation.
    """
    client = _get_client(db)
    ttl = ttl_seconds if ttl_seconds is not None else DEFAULT_TTL
    try:
        client.setex(key, ttl, value)
        return {"status": "ok", "key": key, "ttl_seconds": ttl}
    except Exception as exc:
        return {"error": str(exc), "key": key}


@mcp.tool()
def cache_delete(
    keys: list[str],
    db: int = 0,
) -> dict:
    """Delete one or more keys from Redis cache.

    Args:
        keys: List of cache keys to delete.
        db: Redis database number.

    Returns:
        Number of keys actually deleted.
    """
    client = _get_client(db)
    try:
        deleted = client.delete(*keys)
        return {"status": "ok", "deleted": deleted}
    except Exception as exc:
        return {"error": str(exc)}


@mcp.tool()
def cache_invalidate_pattern(
    pattern: str,
    db: int = 0,
) -> dict:
    """Invalidate (delete) all keys matching a pattern.

    Uses Redis SCAN to safely iterate keys without blocking.

    Args:
        pattern: Glob pattern for keys to invalidate (e.g. 'rag:retrieval:*').
        db: Redis database number.

    Returns:
        Number of keys invalidated.
    """
    client = _get_client(db)
    try:
        deleted = 0
        cursor = 0
        while True:
            cursor, keys = client.scan(cursor=cursor, match=pattern, count=100)
            if keys:
                deleted += client.delete(*keys)
            if cursor == 0:
                break
        return {"status": "ok", "pattern": pattern, "deleted": deleted}
    except Exception as exc:
        return {"error": str(exc), "pattern": pattern}


@mcp.tool()
def cache_response(
    pipeline: str,
    tenant_id: str,
    question: str,
    response_data: str,
    ttl_seconds: int | None = None,
) -> dict:
    """Cache a RAG pipeline response (mirrors ResponseCache behavior).

    Args:
        pipeline: The RAG pipeline name (e.g. 'retrieval', 'corrective').
        tenant_id: Tenant identifier.
        question: The user's question.
        response_data: JSON string of the response data to cache.
        ttl_seconds: TTL in seconds. Uses default if not specified.

    Returns:
        Confirmation with the generated cache key.
    """
    h = hashlib.sha256(f"{tenant_id}:{question}".encode()).hexdigest()
    key = f"rag:{pipeline}:{h}"
    return cache_set(key=key, value=response_data, ttl_seconds=ttl_seconds, db=0)


@mcp.tool()
def get_cached_response(
    pipeline: str,
    tenant_id: str,
    question: str,
) -> dict:
    """Look up a cached RAG pipeline response.

    Args:
        pipeline: The RAG pipeline name (e.g. 'retrieval', 'corrective').
        tenant_id: Tenant identifier.
        question: The user's question.

    Returns:
        The cached response data or null if not found.
    """
    h = hashlib.sha256(f"{tenant_id}:{question}".encode()).hexdigest()
    key = f"rag:{pipeline}:{h}"
    return cache_get(key=key, db=0)


# ─── Resources ───────────────────────────────────────────────────────────────


@mcp.resource("redis://stats")
def get_redis_stats() -> str:
    """Get Redis server statistics (memory usage, key counts, hit rate)."""
    client = _get_client(0)
    try:
        info = client.info()
        stats = {
            "used_memory_human": info.get("used_memory_human", ""),
            "used_memory_peak_human": info.get("used_memory_peak_human", ""),
            "connected_clients": info.get("connected_clients", 0),
            "total_commands_processed": info.get("total_commands_processed", 0),
            "keyspace_hits": info.get("keyspace_hits", 0),
            "keyspace_misses": info.get("keyspace_misses", 0),
            "hit_rate": round(
                info.get("keyspace_hits", 0)
                / max(info.get("keyspace_hits", 0) + info.get("keyspace_misses", 0), 1),
                4,
            ),
            "db_key_counts": {},
        }
        for db_key in range(8):
            db_info = info.get(f"db{db_key}")
            if db_info:
                stats["db_key_counts"][f"db{db_key}"] = db_info.get("keys", 0)
    except Exception as exc:
        stats = {"error": str(exc)}
    return json.dumps(stats, indent=2)


@mcp.tool()
def health_check() -> dict:
    """Check Redis connectivity and health.

    Returns:
        Health status with ping result.
    """
    try:
        client = _get_client(0)
        client.ping()
        return {"status": "UP", "backend": "redis"}
    except Exception as exc:
        return {"status": "DOWN", "backend": "redis", "error": str(exc)}


if __name__ == "__main__":
    mcp.run(transport="sse")
