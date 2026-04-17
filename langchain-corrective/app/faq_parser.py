import re
from pathlib import Path

from langchain_core.documents import Document


def parse_faq_documents(path: str) -> list[Document]:
    file_path = Path(path)
    if not file_path.exists():
        raise FileNotFoundError(f"FAQ source file not found: {path}")

    content = file_path.read_text(encoding="utf-8")
    qa_pattern = re.compile(r"\n\s*\d+\.\s*\*\*(.*?)\*\*\s*\n\s*(.+?)(?=\n\s*\d+\.\s*\*\*|\Z)", re.DOTALL)

    docs: list[Document] = []
    for idx, match in enumerate(qa_pattern.finditer("\n" + content), start=1):
        question = re.sub(r"\s+", " ", match.group(1)).strip()
        answer = re.sub(r"\s+", " ", match.group(2)).strip()
        docs.append(
            Document(
                page_content=f"Q: {question}\nA: {answer}",
                metadata={"id": idx, "question": question},
            )
        )

    if not docs:
        raise ValueError("No FAQ Q&A entries found in the source file")

    return docs
