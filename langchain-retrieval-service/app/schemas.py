from pydantic import BaseModel, Field


class RetrievalQueryRequest(BaseModel):
    tenantId: str = Field(default="default")
    question: str = Field(..., min_length=2)
    queryContext: str | None = None
    topK: int = Field(default=4, ge=1, le=20)
    similarityThreshold: float = Field(default=0.35, ge=0.0, le=1.0)


class RetrievedChunk(BaseModel):
    rank: int
    source: str
    chunkNumber: int | None = None
    score: float
    excerpt: str


class RetrievalQueryResponse(BaseModel):
    tenantId: str
    question: str
    transformedQuery: str
    strategy: str
    answer: str
    chunksUsed: int
    grounded: bool
    retrievalLatencyMs: int
    generationLatencyMs: int
    chunks: list[RetrievedChunk]
