"""Reranker sidecar — FastAPI service exposing BGE cross-encoder reranking."""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel, Field

from app.reranker import load_model, rerank

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_model()
    yield


app = FastAPI(title="BGE Cross-Encoder Reranker", version="1.0.0", lifespan=lifespan)


class RerankRequest(BaseModel):
    query: str = Field(..., min_length=1, max_length=2000)
    documents: list[str] = Field(..., min_length=1, max_length=100)
    top_k: int = Field(default=6, ge=1, le=50)


class ScoredDocument(BaseModel):
    index: int
    content: str
    score: float


class RerankResponse(BaseModel):
    results: list[ScoredDocument]


@app.post("/rerank", response_model=RerankResponse)
async def rerank_endpoint(request: RerankRequest) -> RerankResponse:
    results = rerank(request.query, request.documents, request.top_k)
    return RerankResponse(results=[ScoredDocument(**r) for r in results])


@app.get("/health")
async def health():
    return {"status": "UP"}
