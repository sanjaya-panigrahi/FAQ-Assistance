from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import StreamingResponse

from ..pipelines.retrieval import RetrievalPipeline
from ..security import TokenPayload, get_current_user, get_current_user_optional
from ..schemas import RetrievalQueryRequest, RetrievalQueryResponse

router = APIRouter(prefix="/retrieval")
pipeline = RetrievalPipeline()


@router.get("/actuator/health")
async def health() -> dict:
    return await run_in_threadpool(pipeline.health)


@router.post("/api/index/rebuild")
async def rebuild(current_user: TokenPayload = Depends(get_current_user)) -> dict:
    if current_user.role != "ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required")
    try:
        count = await run_in_threadpool(pipeline.rebuild_index)
        return {"status": "ok", "documents": count, "note": "Index managed by faq-ingestion service"}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/retrieval/query", response_model=RetrievalQueryResponse)
async def retrieval_query(
    request: RetrievalQueryRequest,
    current_user: TokenPayload | None = Depends(get_current_user_optional),
) -> RetrievalQueryResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")
    tenant_id = request.tenantId or (current_user.tenant_id if current_user else None)
    if not tenant_id or tenant_id == "default":
        raise HTTPException(status_code=400, detail="tenantId is required")
    if current_user and request.tenantId not in {"", "default", current_user.tenant_id}:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Tenant mismatch")
    request = request.model_copy(update={"tenantId": tenant_id})
    try:
        return await run_in_threadpool(pipeline.query, request)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/retrieval/query-stream")
async def retrieval_query_stream(
    request: RetrievalQueryRequest,
    current_user: TokenPayload | None = Depends(get_current_user_optional),
):
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")
    tenant_id = request.tenantId or (current_user.tenant_id if current_user else None)
    if not tenant_id or tenant_id == "default":
        raise HTTPException(status_code=400, detail="tenantId is required")
    if current_user and request.tenantId not in {"", "default", current_user.tenant_id}:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Tenant mismatch")
    request = request.model_copy(update={"tenantId": tenant_id})
    return StreamingResponse(
        pipeline.query_stream(request),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )
