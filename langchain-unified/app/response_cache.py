"""Redis-backed response cache for all RAG pipelines."""

import hashlib
import json
import os

import redis


class ResponseCache:
    """Caches full RAG responses in Redis (DB 0) keyed by pipeline+tenant+question."""

    def __init__(self) -> None:
        host = os.getenv("REDIS_HOST", "redis-cache")
        port = int(os.getenv("REDIS_PORT", "6379"))
        self._ttl = int(os.getenv("RETRIEVAL_CACHE_TTL_SECONDS", "400"))
        try:
            self._client = redis.Redis(host=host, port=port, db=0, decode_responses=True)
            self._client.ping()
        except Exception:
            self._client = None

    @staticmethod
    def _key(pipeline: str, tenant: str, question: str) -> str:
        h = hashlib.sha256(f"{tenant}:{question}".encode()).hexdigest()
        return f"rag:{pipeline}:{h}"

    def get(self, pipeline: str, tenant: str, question: str) -> dict | None:
        if not self._client:
            return None
        try:
            raw = self._client.get(self._key(pipeline, tenant, question))
            return json.loads(raw) if raw else None
        except Exception:
            return None

    def put(self, pipeline: str, tenant: str, question: str, data: dict) -> None:
        if not self._client:
            return
        try:
            self._client.setex(
                self._key(pipeline, tenant, question),
                self._ttl,
                json.dumps(data),
            )
        except Exception:
            pass


response_cache = ResponseCache()
