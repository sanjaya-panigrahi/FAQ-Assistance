from pydantic import BaseModel, Field


class RagRequest(BaseModel):
    question: str = Field(min_length=1, max_length=500)


class RagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
    selectedSection: str | None = None
