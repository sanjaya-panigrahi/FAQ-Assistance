from __future__ import annotations

from dataclasses import dataclass

from langchain_openai import OpenAIEmbeddings


@dataclass(frozen=True)
class IntentMatch:
    name: str
    score: float


class SemanticIntentMatcher:
    """Embedding-based intent matcher with deterministic fallback behavior."""

    _INTENT_SAMPLES: dict[str, list[str]] = {
        "product_availability": [
            "do you sell new products",
            "are refurbished devices available",
            "new or used product availability",
            "certified refurbished inventory",
        ],
        "policy": [
            "what is your return policy",
            "can i get a refund",
            "replacement for defective item",
            "warranty terms and conditions",
        ],
        "logistics": [
            "shipping time and delivery",
            "track my order status",
            "same day dispatch options",
            "express delivery availability",
        ],
        "general": [
            "tell me about mytechstore",
            "what products do you have",
            "help me with store information",
            "general faq question",
        ],
    }

    def __init__(self, model: str, threshold: float = 0.72) -> None:
        self._embeddings = OpenAIEmbeddings(model=model)
        self._threshold = threshold
        self._intent_vectors = self._build_intent_vectors()

    def match(self, question: str) -> IntentMatch:
        text = (question or "").strip()
        if not text:
            return IntentMatch(name="general", score=0.0)

        query_vec = self._embeddings.embed_query(text)
        best_intent = "general"
        best_score = -1.0

        for intent, vector in self._intent_vectors.items():
            score = self._cosine_similarity(query_vec, vector)
            if score > best_score:
                best_score = score
                best_intent = intent

        if best_score < self._threshold:
            return IntentMatch(name="general", score=best_score)

        return IntentMatch(name=best_intent, score=best_score)

    def _build_intent_vectors(self) -> dict[str, list[float]]:
        vectors: dict[str, list[float]] = {}
        for intent, samples in self._INTENT_SAMPLES.items():
            sample_vectors = self._embeddings.embed_documents(samples)
            vectors[intent] = self._mean_vector(sample_vectors)
        return vectors

    @staticmethod
    def _mean_vector(vectors: list[list[float]]) -> list[float]:
        if not vectors:
            return []
        size = len(vectors[0])
        acc = [0.0] * size
        for vec in vectors:
            for idx in range(size):
                acc[idx] += vec[idx]
        inv = 1.0 / len(vectors)
        return [v * inv for v in acc]

    @staticmethod
    def _cosine_similarity(v1: list[float], v2: list[float]) -> float:
        if not v1 or not v2 or len(v1) != len(v2):
            return -1.0
        dot = 0.0
        norm1 = 0.0
        norm2 = 0.0
        for a, b in zip(v1, v2):
            dot += a * b
            norm1 += a * a
            norm2 += b * b
        if norm1 <= 0.0 or norm2 <= 0.0:
            return -1.0
        return dot / ((norm1 ** 0.5) * (norm2 ** 0.5))
