"""Redis-backed embedding cache wrapping OpenAIEmbeddings."""

import hashlib
import json
import os

import redis
from langchain_openai import OpenAIEmbeddings


class CachedOpenAIEmbeddings(OpenAIEmbeddings):
    """OpenAIEmbeddings with transparent Redis caching (DB 1)."""

    _redis_client: object = None
    _redis_ttl: int = 86400

    def __init__(self, redis_url: str | None = None, redis_ttl: int = 86400, **kwargs):
        super().__init__(**kwargs)
        self._redis_ttl = redis_ttl
        host = os.getenv("REDIS_HOST", "redis-cache")
        port = int(os.getenv("REDIS_PORT", "6379"))
        try:
            self._redis_client = redis.Redis(host=host, port=port, db=1, decode_responses=True)
            self._redis_client.ping()
        except Exception:
            self._redis_client = None

    def _cache_key(self, text: str) -> str:
        h = hashlib.sha256(f"{self.model}:{text}".encode()).hexdigest()
        return f"emb:{h}"

    def embed_query(self, text: str) -> list[float]:
        if self._redis_client:
            try:
                cached = self._redis_client.get(self._cache_key(text))
                if cached:
                    return json.loads(cached)
            except Exception:
                pass

        result = super().embed_query(text)

        if self._redis_client:
            try:
                self._redis_client.setex(self._cache_key(text), self._redis_ttl, json.dumps(result))
            except Exception:
                pass

        return result

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if not self._redis_client:
            return super().embed_documents(texts)

        results: list[list[float] | None] = [None] * len(texts)
        uncached_indices: list[int] = []
        uncached_texts: list[str] = []

        for i, text in enumerate(texts):
            try:
                cached = self._redis_client.get(self._cache_key(text))
                if cached:
                    results[i] = json.loads(cached)
                    continue
            except Exception:
                pass
            uncached_indices.append(i)
            uncached_texts.append(text)

        if uncached_texts:
            new_embeddings = super().embed_documents(uncached_texts)
            for idx, emb in zip(uncached_indices, new_embeddings):
                results[idx] = emb
                try:
                    self._redis_client.setex(
                        self._cache_key(texts[idx]), self._redis_ttl, json.dumps(emb)
                    )
                except Exception:
                    pass

        return results
