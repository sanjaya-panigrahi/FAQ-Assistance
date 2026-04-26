"""BGE Cross-Encoder Reranker — scores (query, document) pairs."""

import logging
from sentence_transformers import CrossEncoder

logger = logging.getLogger(__name__)

_model: CrossEncoder | None = None
MODEL_NAME = "BAAI/bge-reranker-v2-m3"


def load_model() -> CrossEncoder:
    global _model
    if _model is None:
        logger.info("Loading cross-encoder model: %s", MODEL_NAME)
        _model = CrossEncoder(MODEL_NAME)
        logger.info("Model loaded successfully")
    return _model


def rerank(query: str, documents: list[str], top_k: int = 6) -> list[dict]:
    """Score each (query, doc) pair and return top-k by descending score."""
    model = load_model()
    pairs = [[query, doc] for doc in documents]
    scores = model.predict(pairs)

    scored = [
        {"index": i, "content": documents[i], "score": float(scores[i])}
        for i in range(len(documents))
    ]
    scored.sort(key=lambda x: x["score"], reverse=True)
    return scored[:top_k]
