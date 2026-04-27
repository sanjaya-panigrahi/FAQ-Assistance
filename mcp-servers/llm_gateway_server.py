"""LLM Gateway MCP Server.

Centralizes all OpenAI API interactions as MCP tools.
Tools: generate_chat, embed_text, grade_relevance, generate_vision
Resources: llm://models
"""

import json
import logging
import os

from openai import OpenAI
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

CHAT_MODEL = os.getenv("OPENAI_CHAT_MODEL", "gpt-4o-mini")
EMBEDDING_MODEL = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
VISION_MODEL = os.getenv("OPENAI_VISION_MODEL", "gpt-4o-mini")

mcp = FastMCP(
    "LLM Gateway MCP Server",
    description="Centralized LLM operations — chat completion, embeddings, grading, vision",
)

_client: OpenAI | None = None


def _get_client() -> OpenAI:
    global _client
    if _client is None:
        _client = OpenAI()  # Uses OPENAI_API_KEY env var
    return _client


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def generate_chat(
    messages: list[dict],
    model: str | None = None,
    temperature: float = 0.3,
    max_tokens: int = 1024,
) -> dict:
    """Generate a chat completion using OpenAI.

    Args:
        messages: List of message dicts with 'role' and 'content' keys.
                  Roles: 'system', 'user', 'assistant'.
        model: Model name to use. Defaults to gpt-4o-mini.
        temperature: Sampling temperature (0.0–2.0).
        max_tokens: Maximum tokens in the response.

    Returns:
        Generated response text and usage metadata.
    """
    client = _get_client()
    try:
        response = client.chat.completions.create(
            model=model or CHAT_MODEL,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
        )
        choice = response.choices[0]
        return {
            "content": choice.message.content,
            "finish_reason": choice.finish_reason,
            "model": response.model,
            "usage": {
                "prompt_tokens": response.usage.prompt_tokens,
                "completion_tokens": response.usage.completion_tokens,
                "total_tokens": response.usage.total_tokens,
            },
        }
    except Exception as exc:
        return {"error": str(exc)}


@mcp.tool()
def embed_text(
    texts: list[str],
    model: str | None = None,
) -> dict:
    """Generate embeddings for one or more texts using OpenAI.

    Args:
        texts: List of text strings to embed.
        model: Embedding model name. Defaults to text-embedding-3-small.

    Returns:
        List of embedding vectors and usage metadata.
    """
    client = _get_client()
    try:
        response = client.embeddings.create(
            model=model or EMBEDDING_MODEL,
            input=texts,
        )
        embeddings = [item.embedding for item in response.data]
        return {
            "embeddings": embeddings,
            "model": response.model,
            "usage": {"total_tokens": response.usage.total_tokens},
        }
    except Exception as exc:
        return {"error": str(exc)}


@mcp.tool()
def grade_relevance(
    question: str,
    documents: list[str],
) -> dict:
    """Grade the relevance of documents to a question using LLM-as-Judge.

    Each document is graded as RELEVANT or IRRELEVANT with a confidence score.

    Args:
        question: The user's question.
        documents: List of document texts to grade.

    Returns:
        List of grading results with relevance labels and scores.
    """
    client = _get_client()
    grades = []
    for doc in documents:
        prompt = (
            "You are a relevance grader. Rate the relevance of this document to the question.\n"
            "Respond with ONLY valid JSON: {\"relevant\": true/false, \"score\": 0.0-1.0, \"reason\": \"...\"}\n\n"
            f"Question: {question}\n\nDocument:\n{doc[:500]}"
        )
        try:
            response = client.chat.completions.create(
                model=CHAT_MODEL,
                messages=[{"role": "user", "content": prompt}],
                temperature=0.0,
                max_tokens=100,
            )
            raw = response.choices[0].message.content.strip()
            if raw.startswith("```"):
                raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
            parsed = json.loads(raw)
            grades.append({
                "relevant": parsed.get("relevant", True),
                "score": max(0.0, min(1.0, float(parsed.get("score", 0.5)))),
                "reason": parsed.get("reason", ""),
            })
        except Exception:
            grades.append({"relevant": True, "score": 0.5, "reason": "grading failed, defaulting to relevant"})
    return {"question": question, "grades": grades}


@mcp.tool()
def generate_vision(
    question: str,
    image_url: str | None = None,
    image_description: str | None = None,
    context: str | None = None,
    model: str | None = None,
) -> dict:
    """Generate a vision-aware response using a multimodal model.

    Args:
        question: The user's question about the image/content.
        image_url: URL of the image to analyze.
        image_description: Text description of the image (if URL not available).
        context: Additional context from FAQ documents.
        model: Vision model to use. Defaults to gpt-4o-mini.

    Returns:
        Generated response combining visual and textual understanding.
    """
    client = _get_client()
    messages = []

    system_msg = "You are a multimodal FAQ assistant. Answer the question using visual and textual context."
    if context:
        system_msg += f"\n\nFAQ Context:\n{context}"
    messages.append({"role": "system", "content": system_msg})

    if image_url:
        messages.append({
            "role": "user",
            "content": [
                {"type": "text", "text": question},
                {"type": "image_url", "image_url": {"url": image_url}},
            ],
        })
    else:
        user_content = question
        if image_description:
            user_content = f"{question}\n\nImage Description: {image_description}"
        messages.append({"role": "user", "content": user_content})

    try:
        response = client.chat.completions.create(
            model=model or VISION_MODEL,
            messages=messages,
            max_tokens=1024,
        )
        return {
            "content": response.choices[0].message.content,
            "model": response.model,
            "usage": {
                "prompt_tokens": response.usage.prompt_tokens,
                "completion_tokens": response.usage.completion_tokens,
                "total_tokens": response.usage.total_tokens,
            },
        }
    except Exception as exc:
        return {"error": str(exc)}


@mcp.tool()
def score_response(
    question: str,
    response_text: str,
    context_docs: str,
) -> dict:
    """Score a RAG response for retrieval quality, groundedness, and safety.

    Uses LLM-as-Judge to evaluate three dimensions:
    - Retrieval Quality: How relevant are the retrieved docs?
    - Groundedness: Is the answer supported by the context?
    - Safety: Is the response safe and appropriate?

    Args:
        question: The original user question.
        response_text: The generated answer to evaluate.
        context_docs: The retrieved context documents used to generate the answer.

    Returns:
        Scores (0.0-1.0) for each dimension with explanations.
    """
    client = _get_client()
    prompt = (
        "You are a RAG quality evaluator. Evaluate the following along three dimensions.\n\n"
        f"### Question\n{question}\n\n"
        f"### Retrieved Context\n{context_docs[:2000]}\n\n"
        f"### Answer\n{response_text}\n\n"
        "Score each dimension from 0.0 to 1.0 and provide a brief explanation.\n"
        "Return ONLY valid JSON:\n"
        '{"retrieval_quality": {"score": 0.0, "explanation": "..."}, '
        '"groundedness": {"score": 0.0, "explanation": "..."}, '
        '"safety": {"score": 0.0, "explanation": "..."}}'
    )
    try:
        response = client.chat.completions.create(
            model=CHAT_MODEL,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0,
            max_tokens=300,
        )
        raw = response.choices[0].message.content.strip()
        if raw.startswith("```"):
            raw = raw.split("\n", 1)[-1].rsplit("```", 1)[0].strip()
        parsed = json.loads(raw)
        return {"scores": parsed}
    except Exception as exc:
        return {"error": str(exc)}


# ─── Resources ───────────────────────────────────────────────────────────────


@mcp.resource("llm://models")
def get_models_resource() -> str:
    """List the configured LLM models and their roles."""
    models = {
        "chat_model": CHAT_MODEL,
        "embedding_model": EMBEDDING_MODEL,
        "vision_model": VISION_MODEL,
    }
    return json.dumps(models, indent=2)


@mcp.tool()
def health_check() -> dict:
    """Check OpenAI API connectivity.

    Returns:
        Health status and configured models.
    """
    try:
        client = _get_client()
        # Lightweight check — list models
        client.models.list()
        return {
            "status": "UP",
            "backend": "openai",
            "chat_model": CHAT_MODEL,
            "embedding_model": EMBEDDING_MODEL,
        }
    except Exception as exc:
        return {"status": "DOWN", "backend": "openai", "error": str(exc)}


if __name__ == "__main__":
    mcp.run(transport="sse")
