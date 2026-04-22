from pydantic import BaseModel, ConfigDict, Field


class RagRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    question: str = Field(min_length=1, max_length=500)
    customerId: str | None = Field(default=None, alias="customer_id")


class RagResponse(BaseModel):
    answer: str
    graphFacts: int
    strategy: str
    orchestrationStrategy: str
