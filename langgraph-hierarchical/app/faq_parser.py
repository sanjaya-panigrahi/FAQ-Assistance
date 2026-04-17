import re
from pathlib import Path

from langchain_core.documents import Document


QUESTION_PATTERN = re.compile(r"\d+\.\s+\*\*.*\*\*")


def parse_faq_documents(path: str) -> list[Document]:
    file_path = Path(path)
    if not file_path.exists():
        raise FileNotFoundError(f"FAQ source file not found: {path}")

    lines = file_path.read_text(encoding="utf-8").splitlines()
    docs: list[Document] = []
    current_section = "General FAQ"
    current_question: str | None = None
    answer_parts: list[str] = []

    def flush_document() -> None:
        nonlocal current_question, answer_parts
        if not current_question:
            return
        answer = " ".join(part.strip() for part in answer_parts if part.strip()).strip()
        if not answer:
            return
        docs.append(
            Document(
                page_content=f"Section: {current_section}\nQuestion: {current_question}\nAnswer: {answer}",
                metadata={
                    "id": len(docs) + 1,
                    "section": current_section,
                    "question": current_question,
                    "answer": answer,
                },
            )
        )

    for raw_line in lines:
        trimmed = raw_line.strip()
        if trimmed.startswith("## "):
            flush_document()
            current_section = trimmed[3:].strip()
            current_question = None
            answer_parts = []
            continue
        if QUESTION_PATTERN.fullmatch(trimmed):
            flush_document()
            current_question = re.sub(r"^\d+\.\s+\*\*(.*?)\*\*$", r"\1", trimmed).strip()
            answer_parts = []
            continue
        if current_question and trimmed:
            answer_parts.append(trimmed)

    flush_document()

    if not docs:
        raise ValueError("No FAQ Q&A entries found in the source file")

    return docs
