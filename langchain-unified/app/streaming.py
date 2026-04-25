"""SSE streaming utilities for LLM response streaming."""

import json
from collections.abc import Generator, Iterator

from langchain_openai import ChatOpenAI


def sse_event(event: str, data: dict | str) -> str:
    """Format a Server-Sent Event."""
    payload = json.dumps(data) if isinstance(data, dict) else data
    return f"event: {event}\ndata: {payload}\n\n"


def stream_llm_response(
    llm: ChatOpenAI,
    messages: list[tuple[str, str]],
    metadata: dict,
) -> Generator[str, None, None]:
    """Stream LLM response as SSE events.

    Yields:
        SSE events: 'meta' (once), 'token' (per chunk), 'done' (once)
    """
    yield sse_event("meta", metadata)

    full_answer = []
    for chunk in llm.stream(messages):
        token = chunk.content
        if token:
            full_answer.append(token)
            yield sse_event("token", {"token": token})

    answer = "".join(full_answer)
    yield sse_event("done", {"answer": answer})
