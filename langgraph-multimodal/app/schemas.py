from pydantic import BaseModel, ConfigDict, Field


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
