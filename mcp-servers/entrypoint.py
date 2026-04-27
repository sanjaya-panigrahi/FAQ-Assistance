"""Entrypoint for running any MCP server with the FastAPI wrapper.

Usage:
    python -m entrypoint chromadb_server 8301
    python -m entrypoint neo4j_server 8302
    python -m entrypoint llm_gateway_server 8303
    python -m entrypoint web_search_server 8304
    python -m entrypoint cache_server 8305
    python -m entrypoint analytics_server 8306
"""

import importlib
import os
import sys

import uvicorn

from shared.server_wrapper import create_app


def main():
    if len(sys.argv) < 2:
        print("Usage: python entrypoint.py <server_module> [port]")
        print("  Servers: chromadb_server, neo4j_server, llm_gateway_server,")
        print("           web_search_server, cache_server, analytics_server")
        sys.exit(1)

    server_module_name = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else int(os.getenv("MCP_PORT", "8301"))

    # Import the MCP server module
    server_module = importlib.import_module(server_module_name)
    mcp_server = server_module.mcp

    service_name = f"mcp-{server_module_name.replace('_server', '')}"

    # Create FastAPI app with auth + metrics
    app = create_app(mcp_server, service_name=service_name, port=port)

    # Mount the MCP server's SSE endpoint
    mcp_app = mcp_server.sse_app()
    app.mount("/mcp", mcp_app)

    print(f"Starting {service_name} on port {port}")
    uvicorn.run(app, host="0.0.0.0", port=port, log_level=os.getenv("LOG_LEVEL", "info").lower())


if __name__ == "__main__":
    main()
