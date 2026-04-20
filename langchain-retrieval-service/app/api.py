from fastapi import APIRouter, HTTPException

from .pipeline import pipeline
from .schemas import RetrievalQueryRequest, RetrievalQueryResponse


router = APIRouter()


@router.get("/actuator/health")
def health() -> dict:
    return pipeline.health()


@router.post("/api/index/rebuild")
def rebuild() -> dict:
    try:
        count = pipeline.rebuild_index()
        return {"status": "ok", "documents": count, "note": "Index managed by faq-ingestion service"}
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/retrieval/query", response_model=RetrievalQueryResponse)
def retrieval_query(request: RetrievalQueryRequest) -> RetrievalQueryResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")

    try:
        return pipeline.query(request)
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
