from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import Response
from contextlib import asynccontextmanager
import os

from .auth import router as auth_router
from .routers.agentic import router as agentic_router
from .routers.retrieval import router as retrieval_router
from .routers.corrective import router as corrective_router
from .routers.hierarchical import router as hierarchical_router
from .routers.multimodal import router as multimodal_router
from .routers.graph import router as graph_router
from .settings import get_settings
from .logging_config import setup_logging, get_logger
from .metrics import MetricsMiddleware, get_metrics
from .tracing import setup_tracing, shutdown_tracing

setup_logging(
    service_name="langchain-unified",
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


app = FastAPI(
    lifespan=lifespan,
    title="LangChain Unified RAG",
    version="1.0.0",
    description="All LangChain RAG patterns in a single service",
    docs_url="/api/docs",
    redoc_url="/api/redoc",
    openapi_url="/api/openapi.json"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=settings.cors_allow_credentials,
    allow_methods=settings.cors_allow_methods,
    allow_headers=settings.cors_allow_headers,
)

app.add_middleware(MetricsMiddleware)

# Auth router (shared)
app.include_router(auth_router)

# Pattern routers — each mounted at its prefix (/agentic, /retrieval, etc.)
app.include_router(agentic_router)
app.include_router(retrieval_router)
app.include_router(corrective_router)
app.include_router(hierarchical_router)
app.include_router(multimodal_router)
app.include_router(graph_router)

setup_tracing(
    app,
    service_name=settings.service_name,
    zipkin_host=settings.zipkin_host,
    zipkin_port=settings.zipkin_port,
    sample_rate=settings.tracing_sample_rate,
    enabled=settings.tracing_enabled,
)


@app.get("/actuator/health")
async def root_health() -> dict:
    return {"status": "UP", "service": "langchain-unified"}


@app.get("/metrics")
async def metrics():
    return Response(content=get_metrics(), media_type="text/plain; version=0.0.4")
