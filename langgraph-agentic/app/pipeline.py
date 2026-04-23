import json
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

import chromadb
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse
from .faq_pattern_registry import get_registry
from .semantic_intents import SemanticIntentMatcher


def _extract_product_availability_answer(question: str, context: str) -> str | None:
    q = (question or "").lower()
    c = (context or "").lower()
    is_product_availability = (
        ("product" in q or "products" in q)
        and ("refurb" in q or "new" in q or "used" in q or "pre-owned" in q)
    )
    if not is_product_availability:
        return None

    has_availability_fact = "new products" in c and "refurbished" in c
    has_warranty_fact = "minimum 6-month warranty" in c or "6-month warranty" in c
    if has_availability_fact and has_warranty_fact:
        return (
            "We sell both new products and refurbished products. "
            "Refurbished devices are certified, tested, and include a minimum 6-month warranty."
        )
    if has_availability_fact:
        return "We sell both new products and refurbished products."
    return None


class AgenticPipeline:
    CHROMA_TENANT = "default_tenant"
    CHROMA_DATABASE = "default_database"

    def __init__(self) -> None:
        self._intent_matcher = SemanticIntentMatcher(model=settings.openai_embedding_model)

    def health(self) -> dict:
        try:
            self._chroma_get("/api/v2/heartbeat")
            return {"status": "UP", "backend": "chromadb"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb", "error": str(exc)}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        route = self._route(question)
        retrieval_query = self._expand_query(question, route)

        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        collection_payload = self._get_collection(collection)
        if not collection_payload:
            return RagResponse(
                answer="I could not find grounded FAQ evidence for that customer.",
                chunksUsed=0,
                strategy="chroma-v2-rest+langgraph-routing",
                orchestrationStrategy="langgraph-multistep-routing",
            )

        collection_id = str(collection_payload.get("id") or "").strip()
        if not collection_id:
            raise RuntimeError(f"Collection id missing for {collection}")

        query_embedding = embeddings.embed_query(retrieval_query)
        query_payload = self._query_collection(
            collection_id=collection_id,
            query_embedding=query_embedding,
            top_k=6,
        )
        docs = self._extract_documents(query_payload)
        context = "\n\n".join(doc["content"] for doc in docs)

        if not context:
            return RagResponse(
                answer="I could not find grounded FAQ evidence for that question. Please refine the question or ingest more tenant data.",
                chunksUsed=0,
                strategy="chroma-v2-rest+langgraph-routing",
                orchestrationStrategy="langgraph-multistep-routing",
            )

        # Try structured extraction using pattern registry
        registry = get_registry()
        structured_answer = registry.extract_faq_answer(question, context)
        if structured_answer and "No structured answer" not in structured_answer:
            return RagResponse(
                answer=structured_answer,
                chunksUsed=len(docs),
                strategy="pattern-registry+structured-extraction",
                orchestrationStrategy="langgraph-multistep-routing",
            )

        deterministic_answer = _extract_product_availability_answer(question, context)
        if deterministic_answer:
            return RagResponse(
                answer=deterministic_answer,
                chunksUsed=len(docs),
                strategy=f"semantic-intent+deterministic-extraction:{route}",
                orchestrationStrategy="langgraph-multistep-routing",
            )

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        customer_label = (tenant or "the company").strip()
        answer = llm.invoke(
            (
                f"You are a support assistant for {customer_label}. Answer only from FAQ context and do not guess. "
                "If a general policy is present, apply it directly to the asked product type. "
                "Do not invent policy windows or generic caveats unless they appear in context.\n\n"
                f"Route: {route}\n"
                f"Question: {question}\n\n"
                f"FAQ Context:\n{context}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            chunksUsed=len(docs),
            strategy="chroma-v2-rest+langgraph-routing",
            orchestrationStrategy="langgraph-multistep-routing",
        )

    def _chroma_base(self) -> str:
        return (
            f"http://{settings.chroma_host}:{settings.chroma_port}"
            f"/api/v2/tenants/{self.CHROMA_TENANT}/databases/{self.CHROMA_DATABASE}/collections"
        )

    def _chroma_get(self, path: str) -> dict:
        return self._request_json("GET", f"http://{settings.chroma_host}:{settings.chroma_port}{path}")

    def _request_json(self, method: str, url: str, payload: dict | None = None) -> dict:
        data = None
        headers = {}
        if payload is not None:
            data = json.dumps(payload).encode("utf-8")
            headers["Content-Type"] = "application/json"

        request = Request(url, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=30) as response:
                body = response.read().decode("utf-8")
                return json.loads(body) if body else {}
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"Chroma request failed: {exc.code} {body}") from exc
        except URLError as exc:
            raise RuntimeError(f"Chroma request failed: {exc.reason}") from exc

    def _get_collection(self, collection_name: str) -> dict | None:
        encoded_name = quote(collection_name, safe="")
        try:
            return self._request_json("GET", f"{self._chroma_base()}/{encoded_name}")
        except RuntimeError as exc:
            if " 404 " in str(exc):
                return None
            raise

    def _query_collection(self, collection_id: str, query_embedding: list[float], top_k: int) -> dict:
        return self._request_json(
            "POST",
            f"{self._chroma_base()}/{collection_id}/query",
            {
                "query_embeddings": [query_embedding],
                "n_results": top_k,
                "include": ["documents", "metadatas", "distances"],
            },
        )

    def _extract_documents(self, query_payload: dict) -> list[dict]:
        documents = (query_payload.get("documents") or [[]])[0]
        metadatas = (query_payload.get("metadatas") or [[]])[0]
        distances = (query_payload.get("distances") or [[]])[0]

        extracted_docs: list[dict] = []
        for index, content in enumerate(documents):
            if not content:
                continue
            metadata = metadatas[index] if index < len(metadatas) else {}
            distance = distances[index] if index < len(distances) else None
            extracted_docs.append(
                {
                    "content": str(content),
                    "metadata": metadata or {},
                    "distance": distance,
                }
            )
        return extracted_docs

    def _route(self, question: str) -> str:
        intent = self._intent_matcher.match(question)
        if intent.name == "product_availability":
            return "product_availability"
        if intent.name == "policy":
            return "policy"
        if intent.name == "logistics":
            return "logistics"

        q = question.lower()
        if any(t in q for t in ["return", "refund", "replace", "warranty"]):
            return "policy"
        if any(t in q for t in ["delivery", "shipping", "track", "dispatch"]):
            return "logistics"
        return "general"

    def _expand_query(self, question: str, route: str) -> str:
        q = question.lower()
        if route == "product_availability" or (("product" in q or "products" in q) and (
            "refurb" in q or "new" in q or "used" in q or "pre-owned" in q
        )):
            return (
                f"{question} products availability new products refurbished products "
                "certified refurbished"
            )
        if route == "policy":
            return f"{question} return policy refund replacement warranty"
        if route == "logistics":
            return f"{question} shipping delivery tracking"
        return question




pipeline = AgenticPipeline()
