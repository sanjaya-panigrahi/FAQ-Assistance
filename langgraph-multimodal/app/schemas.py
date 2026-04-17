from pydantic import BaseModel


class VisionRagRequest(BaseModel):
    question: str
    imageDescription: str = ""


class VisionRagResponse(BaseModel):
    answer: str
    chunksUsed: int
    strategy: str
