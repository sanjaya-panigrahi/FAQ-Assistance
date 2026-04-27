"""Web Search MCP Server.

Exposes web search operations (Tavily API) as MCP tools.
Used by the Corrective RAG pattern for fallback web search.
Tools: web_search, fetch_url_content
"""

import json
import logging
import os

import requests
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

TAVILY_API_KEY = os.getenv("TAVILY_API_KEY", "")
TAVILY_SEARCH_URL = "https://api.tavily.com/search"

mcp = FastMCP(
    "Web Search MCP Server",
    description="Web search operations via Tavily API for RAG fallback",
)


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def web_search(
    query: str,
    max_results: int = 3,
    search_depth: str = "basic",
    include_answer: bool = True,
) -> dict:
    """Search the web using Tavily API.

    Used as a fallback when local FAQ documents are insufficient
    (e.g., in Corrective RAG pattern).

    Args:
        query: The search query string.
        max_results: Maximum number of search results (1-10).
        search_depth: Search depth - 'basic' or 'advanced'.
        include_answer: Whether to include Tavily's AI-generated answer.

    Returns:
        Search results with titles, content, URLs, and optional AI answer.
    """
    if not TAVILY_API_KEY:
        return {"error": "TAVILY_API_KEY not configured", "results": []}

    max_results = max(1, min(10, max_results))
    if search_depth not in ("basic", "advanced"):
        search_depth = "basic"

    try:
        resp = requests.post(
            TAVILY_SEARCH_URL,
            json={
                "api_key": TAVILY_API_KEY,
                "query": query,
                "max_results": max_results,
                "search_depth": search_depth,
                "include_answer": include_answer,
            },
            timeout=15,
        )
        resp.raise_for_status()
        data = resp.json()

        results = []
        for r in data.get("results", []):
            results.append({
                "title": r.get("title", ""),
                "content": r.get("content", ""),
                "url": r.get("url", ""),
                "score": r.get("score", 0.0),
            })

        return {
            "query": query,
            "answer": data.get("answer", "") if include_answer else None,
            "results": results,
            "count": len(results),
        }
    except requests.exceptions.Timeout:
        return {"error": "Tavily API request timed out", "results": []}
    except requests.exceptions.HTTPError as exc:
        return {"error": f"Tavily API error: {exc.response.status_code}", "results": []}
    except Exception as exc:
        return {"error": str(exc), "results": []}


@mcp.tool()
def fetch_url_content(
    url: str,
    max_length: int = 5000,
) -> dict:
    """Fetch and extract text content from a URL.

    Args:
        url: The URL to fetch content from.
        max_length: Maximum character length of extracted content.

    Returns:
        Extracted text content from the URL.
    """
    # Validate URL scheme for security
    if not url.startswith(("http://", "https://")):
        return {"error": "Only HTTP and HTTPS URLs are allowed"}

    try:
        resp = requests.get(
            url,
            timeout=10,
            headers={"User-Agent": "FAQ-Assistance-MCP/1.0"},
        )
        resp.raise_for_status()
        content = resp.text[:max_length]
        return {
            "url": url,
            "content": content,
            "content_length": len(content),
            "content_type": resp.headers.get("Content-Type", ""),
            "status_code": resp.status_code,
        }
    except requests.exceptions.Timeout:
        return {"error": "Request timed out", "url": url}
    except requests.exceptions.HTTPError as exc:
        return {"error": f"HTTP error: {exc.response.status_code}", "url": url}
    except Exception as exc:
        return {"error": str(exc), "url": url}


@mcp.tool()
def health_check() -> dict:
    """Check Tavily API configuration status.

    Returns:
        Health status indicating whether Tavily API key is configured.
    """
    return {
        "status": "UP" if TAVILY_API_KEY else "DEGRADED",
        "backend": "tavily",
        "api_key_configured": bool(TAVILY_API_KEY),
    }


if __name__ == "__main__":
    mcp.run(transport="sse")
