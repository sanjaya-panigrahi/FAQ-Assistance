from fastapi import FastAPI, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from contextlib import asynccontextmanager
import os

from .api import router
from .auth import router as auth_router
from .settings import get_settings
from .logging_config import setup_logging, get_logger
from .metrics import MetricsMiddleware, get_metrics
from .security import get_current_user_optional, TokenPayload
from .tracing import setup_tracing, shutdown_tracing

# Setup logging
setup_logging(
    service_name="langchain-hierarchical",
    log_level=os.getenv("LOG_LEVEL", "INFO")
)
logger = get_logger("main")

settings = get_settings()

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "Application starting",
        service=settings.service_name,
        version=settings.service_version,
        environment=settings.environment
    )
    yield
    shutdown_tracing()
    logger.info("Application shutting down", service=settings.service_name)

# Create FastAPI app
app = FastAPI(
    lifespan=lifespan,
    title="LangChain Hierarchical RAG",
    version="1.0.0",
    description="Hierarchical RAG service using LangChain",
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json"
)

# Add middleware for CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=settings.cors_allow_credentials,
    allow_methods=settings.cors_allow_methods,
    allow_headers=settings.cors_allow_headers,
)

# Add metrics middleware
app.add_middleware(MetricsMiddleware)

# Include routers
app.include_router(auth_router)
app.include_router(router)

# Setup distributed tracing
setup_tracing(
    app,
    service_name=settings.service_name,
    zipkin_host=settings.zipkin_host,
    zipkin_port=settings.zipkin_port,
    sample_rate=settings.tracing_sample_rate,
    enabled=settings.tracing_enabled,
)


# Metrics endpoint
@app.get("/metrics")
async def metrics():
    """Prometheus metrics endpoint."""
    return Response(get_metrics(), media_type="text/plain")


# Health check with metrics
@app.get("/health")
async def health_check(current_user: TokenPayload = Depends(get_current_user_optional)):
    """Health check endpoint with optional authentication."""
    logger.info("health check", user=current_user.sub if current_user else None)
    return {
        "status": "healthy",
        "service": settings.service_name,
        "version": settings.service_version,
        "environment": settings.environment
    }


# Root endpoint
@app.get("/")
async def root():
    """Root endpoint."""
    return {
        "service": settings.service_name,
        "version": settings.service_version,
        "status": "running",
        "docs": "/api/docs",
        "metrics": "/metrics"
    }

