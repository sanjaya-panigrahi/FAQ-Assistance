"""Tests for the MCP client module and MCP-backed pipelines.

Unit tests for McpClient, McpToolProvider, and feature flag behavior.
These tests use mocked HTTP responses (no live MCP servers needed).
"""

import json
from unittest.mock import MagicMock, patch

import pytest

from app.mcp_client import McpClient, McpToolProvider, McpToolResult, is_mcp_enabled


class TestMcpClient:
    """Test the low-level MCP client HTTP wrapper."""

    def test_init(self):
        client = McpClient("test", "http://localhost:9999")
        assert client.server_name == "test"
        assert client.base_url == "http://localhost:9999"

    @patch("app.mcp_client.httpx.Client")
    def test_call_tool_success(self, mock_client_cls):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "jsonrpc": "2.0",
            "id": 1,
            "result": {
                "content": [{"type": "text", "text": json.dumps({"status": "UP"})}]
            },
        }
        mock_response.raise_for_status = MagicMock()

        mock_client = MagicMock()
        mock_client.post.return_value = mock_response
        mock_client_cls.return_value = mock_client

        client = McpClient("test", "http://localhost:9999")
        client._client = mock_client

        result = client.call_tool("health_check", {})
        assert result.success is True
        assert result.data["status"] == "UP"

    @patch("app.mcp_client.httpx.Client")
    def test_call_tool_error(self, mock_client_cls):
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "jsonrpc": "2.0",
            "id": 1,
            "error": {"code": -32600, "message": "Invalid request"},
        }
        mock_response.raise_for_status = MagicMock()

        mock_client = MagicMock()
        mock_client.post.return_value = mock_response
        mock_client_cls.return_value = mock_client

        client = McpClient("test", "http://localhost:9999")
        client._client = mock_client

        result = client.call_tool("bad_tool", {})
        assert result.success is False
        assert "Invalid request" in result.error


class TestMcpToolProvider:
    """Test the high-level MCP tool provider."""

    def test_provider_creates_all_clients(self):
        provider = McpToolProvider()
        assert provider.chromadb is not None
        assert provider.neo4j is not None
        assert provider.llm_gateway is not None
        assert provider.web_search is not None
        assert provider.cache is not None
        assert provider.analytics is not None


class TestFeatureFlag:
    """Test USE_MCP feature flag behavior."""

    @patch.dict("os.environ", {"USE_MCP": "false"})
    def test_mcp_disabled_by_default(self):
        # Re-import to pick up env change
        import importlib
        import app.mcp_client
        importlib.reload(app.mcp_client)
        assert app.mcp_client.is_mcp_enabled() is False

    @patch.dict("os.environ", {"USE_MCP": "true"})
    def test_mcp_enabled(self):
        import importlib
        import app.mcp_client
        importlib.reload(app.mcp_client)
        assert app.mcp_client.is_mcp_enabled() is True
