"""LLM-based reranker for retrieved documents.

Scores each document's relevance to the question using the LLM as a cross-encoder,
then returns the top-K most relevant documents in ranked order.
Scoring calls run in parallel to minimize latency.
"""
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from langchain_openai import ChatOpenAI

logger = logging.getLogger(__name__)

RERANK_PROMPT = (
    "Rate the relevance of this document to the given question on a scale of 0-10.\n"
    "Respond with ONLY a number (0-10).\n\n"
    "Question: {question}\n\nDocument:\n{document}"
)


def rerank_documents(
    llm: ChatOpenAI,
    question: str,
    docs: list,
    top_k: int = 4,
) -> list:
    """Rerank documents by LLM-scored relevance. Returns top_k most relevant."""
    if not docs or len(docs) <= 1:
        return docs[:top_k]

    def _score_doc(doc):
        text = doc.page_content if hasattr(doc, "page_content") else str(doc)
        prompt = RERANK_PROMPT.format(question=question, document=text[:500])
        try:
            result = llm.invoke(prompt).content.strip()
            score = float(result.split()[0])
            return doc, max(0, min(10, score))
        except Exception:
            return doc, 5.0

    with ThreadPoolExecutor(max_workers=min(len(docs), 6)) as pool:
        futures = [pool.submit(_score_doc, doc) for doc in docs]
        scored = [f.result() for f in futures]

    scored.sort(key=lambda x: x[1], reverse=True)
    return [doc for doc, _ in scored[:top_k]]
