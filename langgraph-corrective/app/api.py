from fastapi import APIRouter, Depends, HTTPException, status

from .pipeline import pipeline
from .security import TokenPayload, get_current_user, get_current_user_optional
from .schemas import RagRequest, RagResponse


router = APIRouter()
ORCHESTRATION_STRATEGY = "langgraph-retry-nodes"


@router.get("/actuator/health")
def health() -> dict:
    return pipeline.health()


@router.post("/api/index/rebuild")
def rebuild(current_user: TokenPayload = Depends(get_current_user)) -> dict:
    if current_user.role != "ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required")
    try:
        count = pipeline.rebuild_index()
        return {"status": "ok", "documents": count, "note": "Index managed by faq-ingestion service"}
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/query/ask", response_model=RagResponse)
def ask(request: RagRequest, current_user: TokenPayload | None = Depends(get_current_user_optional)) -> RagResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")

    customer_id = request.customerId or (current_user.tenant_id if current_user else None)
    if not customer_id:
        raise HTTPException(status_code=400, detail="customerId is required")

    try:
        return pipeline.ask(question, customer_id=customer_id)
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
