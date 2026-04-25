from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.concurrency import run_in_threadpool
from celery.result import AsyncResult

from ..pipelines.graph import GraphPipeline
from ..security import TokenPayload, get_current_user_optional
from ..schemas import RagRequest, GraphRagResponse
from ..tasks import rebuild_index_task

router = APIRouter(prefix="/graph")
pipeline = GraphPipeline()
ORCHESTRATION_STRATEGY = "langgraph-graph-workflow"


@router.get("/actuator/health")
async def health() -> dict:
    return await run_in_threadpool(pipeline.health)


@router.post("/api/index/rebuild")
async def rebuild(current_user: TokenPayload | None = Depends(get_current_user_optional)) -> dict:
    if current_user and current_user.role != "ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required")
    try:
        task = rebuild_index_task.apply_async()
        return {"taskId": task.id, "status": task.status}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.get("/api/tasks/{task_id}")
async def get_task_status(task_id: str, current_user: TokenPayload | None = Depends(get_current_user_optional)) -> dict:
    if current_user and current_user.role != "ADMIN":
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="Admin role required")
    result = AsyncResult(task_id, app=rebuild_index_task.app)
    payload = {"taskId": task_id, "status": result.status}
    if result.successful():
        payload["result"] = result.result
    elif result.failed():
        payload["error"] = str(result.result)
    return payload


@router.post("/api/query/ask", response_model=GraphRagResponse)
async def ask(request: RagRequest, current_user: TokenPayload | None = Depends(get_current_user_optional)) -> GraphRagResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")
    customer_id = request.customerId or (current_user.tenant_id if current_user else None)
    if not customer_id:
        raise HTTPException(status_code=400, detail="customerId is required")
    try:
        return await run_in_threadpool(pipeline.ask, question, customer_id=customer_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
