"""Fire-and-forget analytics event posting.

Posts RAG query events (including contextDocs) to rag-analytics so the
LLM-as-Judge pipeline can score retrieval quality, groundedness, and safety.
Uses only stdlib to avoid adding dependencies.
"""

import json
import logging
import os
import threading
import uuid
from urllib.request import Request, urlopen

logger = logging.getLogger(__name__)

_ANALYTICS_URL = os.getenv("ANALYTICS_URL", "http://rag-analytics:9191")


def post_analytics_event(
    *,
    question: str,
    response_text: str,
    customer_id: str,
    rag_pattern: str,
    framework: str,
    strategy: str,
    latency_ms: int,
    context_docs: str,
    status: str = "success",
) -> None:
    """Post an analytics event in a background daemon thread."""
    payload = json.dumps(
        {
            "events": [
                {
                    "requestId": str(uuid.uuid4()),
                    "query": question,
                    "response": response_text,
                    "customer": customer_id,
                    "ragPattern": rag_pattern,
                    "framework": framework,
                    "strategy": strategy,
                    "status": status,
                    "latencyMs": max(latency_ms, 0),
                    "contextDocs": context_docs,
                }
            ]
        }
    ).encode()

    def _post() -> None:
        try:
            req = Request(
                f"{_ANALYTICS_URL}/api/analytics/events",
                data=payload,
                headers={"Content-Type": "application/json"},
                method="POST",
            )
            urlopen(req, timeout=5)  # noqa: S310
        except Exception:
            logger.debug("Analytics posting failed", exc_info=True)

    threading.Thread(target=_post, daemon=True).start()
