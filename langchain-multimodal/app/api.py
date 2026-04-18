import base64

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from langchain_core.messages import HumanMessage
from langchain_openai import ChatOpenAI

from .config import settings
from .pipeline import pipeline
from .schemas import VisionRagRequest, VisionRagResponse


router = APIRouter()


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


@router.post("/api/query/ask-with-image", response_model=VisionRagResponse)
async def ask_with_image(
    question: str = Form(...),
    imageDescription: str = Form(default=""),
    image: UploadFile | None = File(default=None),
) -> VisionRagResponse:
    cleaned_question = question.strip()
    if not cleaned_question:
        raise HTTPException(status_code=400, detail="question is required")

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

    try:
        return pipeline.ask(question=cleaned_question, image_description=image_context)
    except Exception as exc:  # pragma: no cover
        raise HTTPException(status_code=500, detail=str(exc)) from exc
