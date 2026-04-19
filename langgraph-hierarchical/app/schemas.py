from pydantic import BaseModel, Field


class RagRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)
    customerId: str | None = None


class RagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
    orchestrationStrategy: str
    selectedSection: str | None = None
