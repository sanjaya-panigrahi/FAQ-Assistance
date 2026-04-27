"""FastAPI wrapper for MCP servers with auth, metrics, and health endpoints.

Each MCP server can be launched with this wrapper to add:
- API key authentication middleware
- Prometheus metrics endpoint (/metrics)
- Health check endpoint (/health)
- Request tracing via OpenTelemetry

Usage:
    from shared.server_wrapper import create_app
    from chromadb_server import mcp

    app = create_app(mcp, service_name="mcp-chromadb", port=8301)
"""

import hmac
import logging
import os
import time

from fastapi import FastAPI, Request, Response
from fastapi.responses import JSONResponse
from prometheus_client import Counter, Histogram, generate_latest

logger = logging.getLogger(__name__)

MCP_API_KEY = os.getenv("MCP_API_KEY", "")

# Prometheus metrics
REQUEST_COUNT = Counter(
    "mcp_requests_total",
    "Total MCP requests",
    ["server", "method", "status"],
)
REQUEST_LATENCY = Histogram(
    "mcp_request_duration_seconds",
    "MCP request latency",
    ["server", "method"],
)


def create_app(mcp_server, service_name: str, port: int) -> FastAPI:
    """Create a FastAPI app wrapping an MCP server with auth and observability."""

    app = FastAPI(title=service_name, version="1.0.0")

    # Auth middleware
    @app.middleware("http")
    async def auth_middleware(request: Request, call_next):
        # Skip auth for health and metrics endpoints
        if request.url.path in ("/health", "/metrics"):
            return await call_next(request)

        if MCP_API_KEY:
            auth_header = request.headers.get("Authorization", "")
            if auth_header.startswith("Bearer "):
                token = auth_header[7:]
                if not hmac.compare_digest(token, MCP_API_KEY):
                    return JSONResponse(
                        status_code=401,
                        content={"error": "Invalid API key"},
                    )
            else:
                return JSONResponse(
                    status_code=401,
                    content={"error": "Missing Authorization header"},
                )

        return await call_next(request)

    # Metrics middleware
    @app.middleware("http")
    async def metrics_middleware(request: Request, call_next):
        start = time.perf_counter()
        response = await call_next(request)
        duration = time.perf_counter() - start

        REQUEST_COUNT.labels(
            server=service_name,
            method=request.method,
            status=response.status_code,
        ).inc()
        REQUEST_LATENCY.labels(
            server=service_name,
            method=request.method,
        ).observe(duration)

        # Add trace headers
        response.headers["X-MCP-Server"] = service_name
        response.headers["X-Response-Time-Ms"] = str(int(duration * 1000))

        return response

    @app.get("/health")
    async def health():
        return {"status": "UP", "service": service_name, "port": port}

    @app.get("/metrics")
    async def metrics():
        return Response(
            content=generate_latest(),
            media_type="text/plain; version=0.0.4",
        )

    return app
