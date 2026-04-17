from fastapi import APIRouter, HTTPException

from .pipeline import pipeline
from .schemas import VisionRagRequest, VisionRagResponse


router = APIRouter()


@router.get("/actuator/health")
def health() -> dict:
    return pipeline.health()


@router.post("/api/index/rebuild")
def rebuild() -> dict:
    try:
        count = pipeline.rebuild_index()
        return {"status": "ok", "documents": count}
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/query/ask", response_model=VisionRagResponse)
def ask(request: VisionRagRequest) -> VisionRagResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")

    try:
        return pipeline.ask(question=question, image_description=request.imageDescription.strip())
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
