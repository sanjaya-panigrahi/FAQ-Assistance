from fastapi import APIRouter, HTTPException

from .pipeline import pipeline
from .schemas import RagRequest, RagResponse


router = APIRouter()
ORCHESTRATION_STRATEGY = "langchain-parent-child-retriever"


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


@router.post("/api/query/ask", response_model=RagResponse)
def ask(request: RagRequest) -> RagResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")

    try:
        return pipeline.ask(question, customer_id=request.customerId)
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
