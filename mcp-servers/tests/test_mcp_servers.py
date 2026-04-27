"""Integration tests for MCP servers.

These tests validate each MCP server's tool invocations via JSON-RPC 2.0.
Run against live MCP servers:
    pytest mcp-servers/tests/ -v

For CI, start MCP servers first:
    docker compose -f docker-compose.master.yml -f docker-compose.mcp.yml up -d
    pytest mcp-servers/tests/ -v --mcp-base-url http://localhost
"""

import json
import os

import pytest
import requests

MCP_BASE = os.getenv("MCP_BASE_URL", "http://localhost")
MCP_API_KEY = os.getenv("MCP_API_KEY", "")

SERVERS = {
    "chromadb": f"{MCP_BASE}:8301",
    "neo4j": f"{MCP_BASE}:8302",
    "llm_gateway": f"{MCP_BASE}:8303",
    "web_search": f"{MCP_BASE}:8304",
    "cache": f"{MCP_BASE}:8305",
    "analytics": f"{MCP_BASE}:8306",
}


def _headers():
    h = {"Content-Type": "application/json"}
    if MCP_API_KEY:
        h["Authorization"] = f"Bearer {MCP_API_KEY}"
    return h


def _call_tool(server: str, tool_name: str, arguments: dict | None = None) -> dict:
    """Call an MCP tool via JSON-RPC 2.0."""
    url = f"{SERVERS[server]}/mcp"
    payload = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "tools/call",
        "params": {"name": tool_name, "arguments": arguments or {}},
    }
    resp = requests.post(url, json=payload, headers=_headers(), timeout=30)
    resp.raise_for_status()
    result = resp.json()
    if "error" in result:
        pytest.fail(f"MCP error: {result['error']}")
    content = result.get("result", {}).get("content", [])
    if content and isinstance(content, list):
        return json.loads(content[0].get("text", "{}"))
    return result.get("result", {})


# ─── Health Check Tests ──────────────────────────────────────────────────────


class TestHealthEndpoints:
    """Verify all MCP servers respond to health checks."""

    @pytest.mark.parametrize("server", SERVERS.keys())
    def test_health_endpoint(self, server):
        resp = requests.get(f"{SERVERS[server]}/health", timeout=10)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "UP"

    @pytest.mark.parametrize("server", SERVERS.keys())
    def test_metrics_endpoint(self, server):
        resp = requests.get(f"{SERVERS[server]}/metrics", timeout=10)
        assert resp.status_code == 200
        assert "mcp_requests_total" in resp.text


# ─── ChromaDB MCP Server Tests ──────────────────────────────────────────────


class TestChromaDBServer:
    """Tests for the ChromaDB MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("chromadb", "health_check")
        assert result["status"] in ("UP", "DOWN")
        assert result["backend"] == "chromadb"

    def test_list_collections(self):
        result = _call_tool("chromadb", "list_collections")
        assert "collections" in result

    def test_similarity_search_missing_collection(self):
        result = _call_tool("chromadb", "similarity_search", {
            "query_texts": ["test query"],
            "tenant_id": "nonexistent_test_tenant",
            "top_k": 3,
        })
        assert "error" in result or "results" in result

    def test_upsert_and_search(self):
        tenant = "mcp_test_tenant"
        # Upsert
        upsert_result = _call_tool("chromadb", "upsert_documents", {
            "tenant_id": tenant,
            "documents": ["How do I return a product?", "What is the warranty policy?"],
            "ids": ["test-1", "test-2"],
            "metadatas": [{"source": "test"}, {"source": "test"}],
        })
        assert upsert_result.get("status") == "ok"
        assert upsert_result.get("upserted") == 2

        # Search
        search_result = _call_tool("chromadb", "similarity_search", {
            "query_texts": ["return policy"],
            "tenant_id": tenant,
            "top_k": 2,
        })
        assert "results" in search_result

        # Cleanup
        _call_tool("chromadb", "delete_documents", {
            "tenant_id": tenant,
            "ids": ["test-1", "test-2"],
        })


# ─── Neo4j MCP Server Tests ─────────────────────────────────────────────────


class TestNeo4jServer:
    """Tests for the Neo4j MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("neo4j", "health_check")
        assert result["status"] in ("UP", "DOWN")
        assert result["backend"] == "neo4j"

    def test_fulltext_search(self):
        result = _call_tool("neo4j", "fulltext_search", {
            "query": "product",
            "limit": 5,
        })
        assert "results" in result

    def test_run_cypher_query_read(self):
        result = _call_tool("neo4j", "run_cypher_query", {
            "cypher": "MATCH (n) RETURN count(n) AS count LIMIT 1",
        })
        assert "results" in result

    def test_run_cypher_query_write_blocked(self):
        result = _call_tool("neo4j", "run_cypher_query", {
            "cypher": "CREATE (n:Test {name: 'should_fail'})",
        })
        assert "error" in result
        assert "Write" in result["error"] or "CREATE" in result["error"]

    def test_get_entity_relationships(self):
        result = _call_tool("neo4j", "get_entity_relationships", {
            "entity_name": "product",
            "direction": "both",
        })
        assert "relationships" in result


# ─── LLM Gateway MCP Server Tests ───────────────────────────────────────────


class TestLLMGatewayServer:
    """Tests for the LLM Gateway MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("llm_gateway", "health_check")
        assert result["status"] in ("UP", "DOWN")

    @pytest.mark.skipif(not os.getenv("OPENAI_API_KEY"), reason="No OpenAI API key")
    def test_generate_chat(self):
        result = _call_tool("llm_gateway", "generate_chat", {
            "messages": [
                {"role": "user", "content": "Say 'hello' and nothing else."},
            ],
            "temperature": 0.0,
            "max_tokens": 10,
        })
        assert "content" in result
        assert "hello" in result["content"].lower()

    @pytest.mark.skipif(not os.getenv("OPENAI_API_KEY"), reason="No OpenAI API key")
    def test_embed_text(self):
        result = _call_tool("llm_gateway", "embed_text", {
            "texts": ["test embedding"],
        })
        assert "embeddings" in result
        assert len(result["embeddings"]) == 1
        assert len(result["embeddings"][0]) > 100  # Embedding dimension

    @pytest.mark.skipif(not os.getenv("OPENAI_API_KEY"), reason="No OpenAI API key")
    def test_grade_relevance(self):
        result = _call_tool("llm_gateway", "grade_relevance", {
            "question": "What is the return policy?",
            "documents": ["You can return items within 30 days.", "The weather is sunny today."],
        })
        assert "grades" in result
        assert len(result["grades"]) == 2


# ─── Web Search MCP Server Tests ────────────────────────────────────────────


class TestWebSearchServer:
    """Tests for the Web Search MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("web_search", "health_check")
        assert result["status"] in ("UP", "DEGRADED")

    @pytest.mark.skipif(not os.getenv("TAVILY_API_KEY"), reason="No Tavily API key")
    def test_web_search(self):
        result = _call_tool("web_search", "web_search", {
            "query": "Python MCP protocol",
            "max_results": 2,
        })
        assert "results" in result
        assert len(result["results"]) <= 2


# ─── Cache MCP Server Tests ─────────────────────────────────────────────────


class TestCacheServer:
    """Tests for the Cache MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("cache", "health_check")
        assert result["status"] in ("UP", "DOWN")

    def test_cache_set_and_get(self):
        # Set
        set_result = _call_tool("cache", "cache_set", {
            "key": "mcp_test_key",
            "value": json.dumps({"test": True}),
            "ttl_seconds": 60,
            "db": 0,
        })
        assert set_result.get("status") == "ok"

        # Get
        get_result = _call_tool("cache", "cache_get", {
            "key": "mcp_test_key",
            "db": 0,
        })
        assert get_result.get("found") is True
        assert get_result["value"]["test"] is True

        # Delete
        del_result = _call_tool("cache", "cache_delete", {
            "keys": ["mcp_test_key"],
            "db": 0,
        })
        assert del_result.get("deleted") >= 1

    def test_cache_response_flow(self):
        _call_tool("cache", "cache_response", {
            "pipeline": "test",
            "tenant_id": "mcp_test",
            "question": "test question?",
            "response_data": json.dumps({"answer": "test answer"}),
            "ttl_seconds": 60,
        })

        result = _call_tool("cache", "get_cached_response", {
            "pipeline": "test",
            "tenant_id": "mcp_test",
            "question": "test question?",
        })
        assert result.get("found") is True

        # Clean up via pattern
        _call_tool("cache", "cache_invalidate_pattern", {
            "pattern": "rag:test:*",
            "db": 0,
        })


# ─── Analytics MCP Server Tests ─────────────────────────────────────────────


class TestAnalyticsServer:
    """Tests for the Analytics MCP server tools."""

    def test_health_check_tool(self):
        result = _call_tool("analytics", "health_check")
        assert result["status"] in ("UP", "DOWN")

    def test_log_rag_event(self):
        result = _call_tool("analytics", "log_rag_event", {
            "query": "What is the return policy?",
            "response": "You can return within 30 days.",
            "customer": "mcp_test",
            "rag_pattern": "retrieval",
            "framework": "mcp-test",
            "strategy": "mcp+test",
            "status": "success",
            "latency_ms": 150,
        })
        assert result.get("status") == "ok" or "error" in result

    def test_get_dashboard(self):
        result = _call_tool("analytics", "get_dashboard", {
            "limit": 5,
        })
        assert "leaderboard" in result or "error" in result

    def test_get_score_distribution(self):
        result = _call_tool("analytics", "get_score_distribution", {
            "days": 7,
        })
        assert "distribution" in result or "error" in result
