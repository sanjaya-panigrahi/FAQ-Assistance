"""MCP Client integration for LangChain Unified service.

Provides an MCP-backed tool provider that discovers and invokes tools
from the 6 MCP servers (ChromaDB, Neo4j, LLM Gateway, Web Search, Cache, Analytics).

When USE_MCP=true, pipeline code can use these MCP tools instead of direct clients.
When USE_MCP=false (default), existing direct clients are used unchanged.
"""

import json
import logging
import os
from dataclasses import dataclass, field
from typing import Any

import httpx

logger = logging.getLogger(__name__)

USE_MCP = os.getenv("USE_MCP", "false").lower() in ("1", "true", "yes")

# MCP Server endpoints (SSE transport over HTTP)
MCP_SERVERS = {
    "chromadb": os.getenv("MCP_CHROMADB_URL", "http://mcp-chromadb:8301"),
    "neo4j": os.getenv("MCP_NEO4J_URL", "http://mcp-neo4j:8302"),
    "llm_gateway": os.getenv("MCP_LLM_GATEWAY_URL", "http://mcp-llm-gateway:8303"),
    "web_search": os.getenv("MCP_WEB_SEARCH_URL", "http://mcp-web-search:8304"),
    "cache": os.getenv("MCP_CACHE_URL", "http://mcp-cache:8305"),
    "analytics": os.getenv("MCP_ANALYTICS_URL", "http://mcp-analytics:8306"),
}

MCP_API_KEY = os.getenv("MCP_API_KEY", "")


@dataclass
class McpToolResult:
    """Result from an MCP tool invocation."""
    success: bool
    data: dict = field(default_factory=dict)
    error: str | None = None


class McpClient:
    """HTTP-based MCP client that calls MCP server tools via JSON-RPC 2.0."""

    def __init__(self, server_name: str, base_url: str):
        self.server_name = server_name
        self.base_url = base_url.rstrip("/")
        self._client = httpx.Client(
            timeout=httpx.Timeout(connect=5.0, read=60.0, write=10.0, pool=10.0),
        )
        self._request_id = 0

    def call_tool(self, tool_name: str, arguments: dict[str, Any] | None = None) -> McpToolResult:
        """Invoke an MCP tool via JSON-RPC 2.0 over HTTP.

        Args:
            tool_name: Name of the tool to call.
            arguments: Tool arguments as a dictionary.

        Returns:
            McpToolResult with the tool's response data.
        """
        self._request_id += 1
        payload = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "method": "tools/call",
            "params": {
                "name": tool_name,
                "arguments": arguments or {},
            },
        }
        headers = {"Content-Type": "application/json"}
        if MCP_API_KEY:
            headers["Authorization"] = f"Bearer {MCP_API_KEY}"

        try:
            resp = self._client.post(
                f"{self.base_url}/mcp",
                json=payload,
                headers=headers,
            )
            resp.raise_for_status()
            result = resp.json()

            if "error" in result:
                return McpToolResult(
                    success=False,
                    error=result["error"].get("message", str(result["error"])),
                )

            # MCP tool results come as content array
            content = result.get("result", {}).get("content", [])
            if content and isinstance(content, list):
                text_content = content[0].get("text", "{}")
                try:
                    data = json.loads(text_content)
                except (json.JSONDecodeError, TypeError):
                    data = {"text": text_content}
                return McpToolResult(success=True, data=data)

            return McpToolResult(success=True, data=result.get("result", {}))

        except httpx.TimeoutException:
            logger.warning("MCP call timed out: %s/%s", self.server_name, tool_name)
            return McpToolResult(success=False, error=f"Timeout calling {tool_name}")
        except Exception as exc:
            logger.warning("MCP call failed: %s/%s: %s", self.server_name, tool_name, exc)
            return McpToolResult(success=False, error=str(exc))

    def read_resource(self, uri: str) -> McpToolResult:
        """Read an MCP resource by URI.

        Args:
            uri: Resource URI (e.g. 'chroma://collections').

        Returns:
            McpToolResult with the resource content.
        """
        self._request_id += 1
        payload = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "method": "resources/read",
            "params": {"uri": uri},
        }
        headers = {"Content-Type": "application/json"}
        if MCP_API_KEY:
            headers["Authorization"] = f"Bearer {MCP_API_KEY}"

        try:
            resp = self._client.post(
                f"{self.base_url}/mcp",
                json=payload,
                headers=headers,
            )
            resp.raise_for_status()
            result = resp.json()
            content = result.get("result", {}).get("contents", [])
            if content:
                text = content[0].get("text", "{}")
                try:
                    data = json.loads(text)
                except (json.JSONDecodeError, TypeError):
                    data = {"text": text}
                return McpToolResult(success=True, data=data)
            return McpToolResult(success=True, data={})
        except Exception as exc:
            return McpToolResult(success=False, error=str(exc))

    def close(self):
        self._client.close()


class McpToolProvider:
    """Manages connections to all MCP servers and provides a unified interface.

    Usage:
        provider = McpToolProvider()
        result = provider.chromadb.call_tool("similarity_search", {"query_texts": ["..."], "tenant_id": "abc"})
    """

    def __init__(self):
        self.chromadb = McpClient("chromadb", MCP_SERVERS["chromadb"])
        self.neo4j = McpClient("neo4j", MCP_SERVERS["neo4j"])
        self.llm_gateway = McpClient("llm_gateway", MCP_SERVERS["llm_gateway"])
        self.web_search = McpClient("web_search", MCP_SERVERS["web_search"])
        self.cache = McpClient("cache", MCP_SERVERS["cache"])
        self.analytics = McpClient("analytics", MCP_SERVERS["analytics"])

    def close(self):
        for client in [self.chromadb, self.neo4j, self.llm_gateway,
                       self.web_search, self.cache, self.analytics]:
            client.close()

    # ─── Convenience wrappers ──────────────────────────────────────────

    def similarity_search(self, query: str, tenant_id: str, top_k: int = 6) -> McpToolResult:
        """Search ChromaDB via MCP."""
        return self.chromadb.call_tool("similarity_search", {
            "query_texts": [query],
            "tenant_id": tenant_id,
            "top_k": top_k,
        })

    def generate_chat(self, messages: list[dict], temperature: float = 0.3) -> McpToolResult:
        """Generate chat completion via MCP LLM Gateway."""
        return self.llm_gateway.call_tool("generate_chat", {
            "messages": messages,
            "temperature": temperature,
        })

    def embed_text(self, texts: list[str]) -> McpToolResult:
        """Generate embeddings via MCP LLM Gateway."""
        return self.llm_gateway.call_tool("embed_text", {"texts": texts})

    def grade_documents(self, question: str, documents: list[str]) -> McpToolResult:
        """Grade document relevance via MCP LLM Gateway."""
        return self.llm_gateway.call_tool("grade_relevance", {
            "question": question,
            "documents": documents,
        })

    def tavily_search(self, query: str, max_results: int = 3) -> McpToolResult:
        """Web search via MCP Web Search server."""
        return self.web_search.call_tool("web_search", {
            "query": query,
            "max_results": max_results,
        })

    def cache_get_response(self, pipeline: str, tenant_id: str, question: str) -> McpToolResult:
        """Get cached response via MCP Cache server."""
        return self.cache.call_tool("get_cached_response", {
            "pipeline": pipeline,
            "tenant_id": tenant_id,
            "question": question,
        })

    def cache_set_response(self, pipeline: str, tenant_id: str, question: str, data: str) -> McpToolResult:
        """Store response in cache via MCP Cache server."""
        return self.cache.call_tool("cache_response", {
            "pipeline": pipeline,
            "tenant_id": tenant_id,
            "question": question,
            "response_data": data,
        })

    def log_analytics(self, **kwargs) -> McpToolResult:
        """Log analytics event via MCP Analytics server."""
        return self.analytics.call_tool("log_rag_event", kwargs)

    def graph_search(self, query: str) -> McpToolResult:
        """Search Neo4j knowledge graph via MCP."""
        return self.neo4j.call_tool("fulltext_search", {"query": query})

    def graph_traverse(self, entity: str, depth: int = 2) -> McpToolResult:
        """Traverse Neo4j graph from an entity via MCP."""
        return self.neo4j.call_tool("traverse_graph", {
            "entity_name": entity,
            "max_depth": depth,
        })


# Singleton — only created when USE_MCP=true
_provider: McpToolProvider | None = None


def get_mcp_provider() -> McpToolProvider | None:
    """Get the MCP tool provider. Returns None if USE_MCP is false."""
    global _provider
    if not USE_MCP:
        return None
    if _provider is None:
        _provider = McpToolProvider()
        logger.info("MCP Tool Provider initialized with servers: %s", list(MCP_SERVERS.keys()))
    return _provider


def is_mcp_enabled() -> bool:
    """Check if MCP mode is enabled."""
    return USE_MCP
