from pydantic import BaseModel, ConfigDict, Field


class RetrievalQueryRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    tenantId: str = Field(default="default", alias="tenant_id")
    question: str = Field(..., min_length=2)
    queryContext: str | None = Field(default=None, alias="query_context")
    topK: int = Field(default=6, ge=1, le=20, alias="top_k")
    similarityThreshold: float = Field(default=0.35, ge=0.0, le=1.0, alias="similarity_threshold")


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
