from pydantic import BaseModel, Field


class VisionRagRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    imageDescription: str = Field(default="", max_length=1000)
    customerId: str | None = None


class VisionRagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
    orchestrationStrategy: str
    consistencyLabel: str | None = None
    consistencyScore: float | None = None
    consistencyReasons: list[str] = Field(default_factory=list)
