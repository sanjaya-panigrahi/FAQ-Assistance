import base64
import json

from fastapi import APIRouter, Depends, File, Form, HTTPException, UploadFile, status
from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI

from .config import settings
from .pipeline import pipeline
from .security import TokenPayload, get_current_user, get_current_user_optional
from .schemas import VisionRagRequest, VisionRagResponse


router = APIRouter()
ORCHESTRATION_STRATEGY = "langchain-multimodal-quickstart"


def _compose_image_context(image_description: str, image: UploadFile | None) -> str:
    details = []
    if image is not None:
        content_type = image.content_type or "unknown"
        filename = image.filename or "unnamed"
        details.append(f"Uploaded image: name={filename}, type={content_type}")

    description = (image_description or "").strip()
    if description:
        details.append(f"User image notes: {description}")

    return "\n".join(details).strip()


def _extract_visual_signals(image_bytes: bytes, content_type: str) -> str:
    if not image_bytes:
        return ""

    llm = ChatOpenAI(model=settings.openai_vision_model, temperature=0)
    image_b64 = base64.b64encode(image_bytes).decode("ascii")
    message = HumanMessage(
        content=[
            {
                "type": "text",
                "text": (
                    "Extract concise visual signals from this support image for RAG use. "
                    "Return one short paragraph covering product type, visible condition, damage signs, "
                    "packaging state, and anything relevant to return/warranty policy."
                ),
            },
            {"type": "image_url", "image_url": {"url": f"data:{content_type};base64,{image_b64}"}},
        ]
    )

    response = llm.invoke([message])
    return str(response.content).strip()


def _parse_json_object(raw_text: str) -> dict:
    text = (raw_text or "").strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.startswith("json"):
            text = text[4:].strip()

    try:
        loaded = json.loads(text)
        return loaded if isinstance(loaded, dict) else {}
    except json.JSONDecodeError:
        return {}


def _evaluate_consistency(user_notes: str, visual_signals: str) -> tuple[str | None, float | None, list[str]]:
    notes = (user_notes or "").strip()
    signals = (visual_signals or "").strip()
    if not notes or not signals:
        return None, None, []

    llm = ChatOpenAI(model=settings.openai_vision_model, temperature=0)
    prompt = (
        "You compare user image notes with extracted visual signals.\n"
        "Return ONLY valid JSON with keys: label, score, reasons.\n"
        "label must be one of: match, partial, mismatch.\n"
        "score must be between 0 and 1.\n"
        "reasons must be an array of short strings.\n\n"
        f"User notes: {notes}\n"
        f"Extracted visual signals: {signals}"
    )
    response = llm.invoke(prompt)
    data = _parse_json_object(str(response.content))

    label = data.get("label") if isinstance(data.get("label"), str) else None
    if label not in {"match", "partial", "mismatch"}:
        label = None

    score_raw = data.get("score")
    score = None
    if isinstance(score_raw, (int, float)):
        score = max(0.0, min(1.0, float(score_raw)))

    reasons_raw = data.get("reasons")
    reasons = [str(item) for item in reasons_raw] if isinstance(reasons_raw, list) else []
    return label, score, reasons


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


@router.post("/api/query/ask", response_model=VisionRagResponse)
def ask(request: VisionRagRequest, current_user: TokenPayload | None = Depends(get_current_user_optional)) -> VisionRagResponse:
    question = request.question.strip()
    if not question:
        raise HTTPException(status_code=400, detail="question is required")

    customer_id = request.customerId or (current_user.tenant_id if current_user else None)
    if not customer_id:
        raise HTTPException(status_code=400, detail="customerId is required")

    try:
        return pipeline.ask(
            question=question,
            image_description=request.imageDescription.strip(),
            customer_id=customer_id,
        )
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@router.post("/api/query/ask-with-image", response_model=VisionRagResponse)
async def ask_with_image(
    question: str = Form(...),
    customerId: str | None = Form(default=None),
    imageDescription: str = Form(default=""),
    image: UploadFile | None = File(default=None),
    current_user: TokenPayload | None = Depends(get_current_user_optional),
) -> VisionRagResponse:
    cleaned_question = question.strip()
    if not cleaned_question:
        raise HTTPException(status_code=400, detail="question is required")

    customer_id = customerId or (current_user.tenant_id if current_user else None)
    if not customer_id:
        raise HTTPException(status_code=400, detail="customerId is required")

    extracted_signals = ""
    if image is not None:
        if image.content_type is None or not image.content_type.startswith("image/"):
            raise HTTPException(status_code=400, detail="uploaded file must be an image")
        image_bytes = await image.read()
        if len(image_bytes) > 8 * 1024 * 1024:
            raise HTTPException(status_code=400, detail="uploaded image must be 8MB or smaller")
        extracted_signals = _extract_visual_signals(image_bytes, image.content_type)

    image_context = _compose_image_context(imageDescription, image)
    if extracted_signals:
        image_context = (image_context + "\n" if image_context else "") + f"Vision extraction: {extracted_signals}"

    consistency_label, consistency_score, consistency_reasons = _evaluate_consistency(
        imageDescription, extracted_signals
    )

    try:
        base = pipeline.ask(
            question=cleaned_question,
            image_description=image_context,
            customer_id=customer_id,
        )
        return VisionRagResponse(
            answer=base.answer,
            chunksUsed=base.chunksUsed,
            strategy=base.strategy,
            orchestrationStrategy=base.orchestrationStrategy,
            consistencyLabel=consistency_label,
            consistencyScore=consistency_score,
            consistencyReasons=consistency_reasons,
        )
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
