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


# Setup logging
setup_logging(
    service_name="langgraph-retrieval-service",
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
    logger.info("Application shutting down", service=settings.service_name)

app = FastAPI(
    lifespan=lifespan,
    title="LangGraph Retrieval Service",
    version="1.0.0",
    description="FAQ retrieval service using LangGraph",
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
app.include_router(auth_router)
app.include_router(router)


@app.get("/metrics")
async def metrics():
    return Response(get_metrics(), media_type="text/plain")


@app.get("/health")
async def health_check(current_user: TokenPayload = Depends(get_current_user_optional)):
    logger.info("health check", user=current_user.sub if current_user else None)
    return {
        "status": "healthy",
        "service": settings.service_name,
        "version": settings.service_version,
        "environment": settings.environment,
    }


@app.get("/")
async def root():
    return {
        "service": settings.service_name,
        "version": settings.service_version,
        "status": "running",
        "docs": "/api/docs",
        "metrics": "/metrics",
    }

