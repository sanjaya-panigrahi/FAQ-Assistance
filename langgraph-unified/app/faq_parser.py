import re
from pathlib import Path


def parse_faq_entries(path: str) -> list[dict]:
    file_path = Path(path)
    if not file_path.exists():
        raise FileNotFoundError(f"FAQ source file not found: {path}")

    content = file_path.read_text(encoding="utf-8")
    qa_pattern = re.compile(r"\n\s*\d+\.\s*\*\*(.*?)\*\*\s*\n\s*(.+?)(?=\n\s*\d+\.\s*\*\*|\Z)", re.DOTALL)

    rows: list[dict] = []
    for idx, match in enumerate(qa_pattern.finditer("\n" + content), start=1):
        question = re.sub(r"\s+", " ", match.group(1)).strip()
        answer = re.sub(r"\s+", " ", match.group(2)).strip()
        rows.append({"id": idx, "question": question, "answer": answer})

    if not rows:
        raise ValueError("No FAQ Q&A entries found in the source file")

    return rows
