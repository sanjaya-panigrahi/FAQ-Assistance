from pydantic import BaseModel, ConfigDict, Field


# --- Standard RAG (agentic, corrective) ---

class RagRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    question: str = Field(min_length=1, max_length=500)
    customerId: str | None = Field(default=None, alias="customer_id")


class RagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
    orchestrationStrategy: str


# --- Hierarchical (adds selectedSection) ---

class HierarchicalRagResponse(RagResponse):
    selectedSection: str | None = None


# --- Graph (uses graphFacts instead of chunksUsed) ---

class GraphRagResponse(BaseModel):
    answer: str
    graphFacts: int
    strategy: str
    orchestrationStrategy: str


# --- Multimodal ---

class VisionRagRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    question: str = Field(min_length=1, max_length=500)
    imageDescription: str = Field(default="", max_length=1000, alias="image_description")
    customerId: str | None = Field(default=None, alias="customer_id")


class VisionRagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
    orchestrationStrategy: str
    consistencyLabel: str | None = None
    consistencyScore: float | None = None
    consistencyReasons: list[str] = Field(default_factory=list)


# --- Retrieval ---

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
