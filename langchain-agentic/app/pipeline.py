import chromadb

from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain.tools import tool
from langchain.tools.retriever import create_retriever_tool
from langchain_chroma import Chroma
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse
from .faq_pattern_registry import get_registry
from .semantic_intents import SemanticIntentMatcher


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


def _expand_retrieval_query(question: str, intent_name: str = "general") -> str:
    q = (question or "").lower()
    extras: list[str] = []
    if intent_name == "product_availability" or (("product" in q or "products" in q) and (
        "refurb" in q or "new" in q or "used" in q or "pre-owned" in q
    )):
        extras.append("products availability new products refurbished products certified refurbished")
    if intent_name == "policy" or ("return" in q or "refund" in q or "replace" in q):
        extras.append("return policy returns refunds replacements defective items unopened items")
    if intent_name == "policy" or ("warranty" in q or "damage" in q or "protection" in q):
        extras.append("warranty extended warranty accidental damage protection repair coverage")
    if intent_name == "logistics" or ("delivery" in q or "shipping" in q):
        extras.append("shipping delivery tracking express same-day order status")
    return " ".join([question, *extras]).strip()


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


# Create extraction tool using pattern registry
@tool
def extract_faq_answer(question: str, context: str) -> str:
    """Extract structured answers from FAQ context using intelligent pattern matching.
    
    Handles: return policies, warranties, shipping times, defect handling, and more.
    Returns formatted answer if pattern matches the question, otherwise indicates no structured answer.
    """
    registry = get_registry()
    result = registry.extract_faq_answer(question, context)
    return result or "No structured answer found for this question type."


class AgenticPipeline:
    def __init__(self) -> None:
        self._intent_matcher = SemanticIntentMatcher(model=settings.openai_embedding_model)

    def health(self) -> dict:
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            client.heartbeat()
            return {"status": "UP", "backend": "chromadb"}
        except Exception:
            return {"status": "DEGRADED", "backend": "chromadb"}

    def rebuild_index(self) -> int:
        return 0  # Index managed by faq-ingestion service (port 9000)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        collection_name = f"{settings.chroma_collection_prefix}{customer_id or 'default'}"
        try:
            client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
            intent = self._intent_matcher.match(question)
            top_k = 8 if intent.name == "product_availability" else 4
            vector_store = Chroma(
                client=client,
                collection_name=collection_name,
                embedding_function=embeddings,
            )
            retriever = vector_store.as_retriever(search_kwargs={"k": top_k})
        except Exception as exc:
            raise RuntimeError(f"ChromaDB connection failed for collection {collection_name}") from exc

        retrieval_query = _expand_retrieval_query(question, intent.name)
        docs = retriever.invoke(retrieval_query)
        if not docs:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                chunksUsed=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langchain-agent",
            )

        combined_context = "\n\n".join(doc.page_content for doc in docs)
        
        # Try structured extraction using pattern registry
        registry = get_registry()
        structured_answer = registry.extract_faq_answer(question, combined_context)
        if structured_answer and "No structured answer" not in structured_answer:
            return RagResponse(
                answer=structured_answer,
                chunksUsed=len(docs),
                strategy="pattern-registry+structured-extraction",
                orchestrationStrategy="langchain-agent",
            )

        deterministic_answer = _extract_product_availability_answer(question, combined_context)
        if deterministic_answer:
            return RagResponse(
                answer=deterministic_answer,
                chunksUsed=len(docs),
                strategy=f"semantic-intent+deterministic-extraction:{intent.name}",
                orchestrationStrategy="langchain-agent",
            )

        retriever_tool = create_retriever_tool(
            retriever,
            "mytechstore_faq_retriever",
            "Use this tool to retrieve answers from MyTechStore FAQ knowledge base.",
        )

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    "You are a MyTechStore support assistant. Always use the FAQ retrieval tool and answer only from retrieved context. "
                    "If a general policy is present, apply it directly to the asked product type. "
                    "Do not invent policy windows or add generic caveats unless those caveats appear in context. "
                    "When you find relevant context, use the extract_faq_answer tool to structure the response if applicable.",
                ),
                ("human", "{input}"),
                MessagesPlaceholder(variable_name="agent_scratchpad"),
            ]
        )

        agent = create_openai_tools_agent(llm, [retriever_tool, extract_faq_answer], prompt)
        executor = AgentExecutor(agent=agent, tools=[retriever_tool, extract_faq_answer], verbose=False)
        result = executor.invoke({"input": question})

        return RagResponse(
            answer=str(result.get("output", "No answer returned.")),
            chunksUsed=len(docs),
            strategy=f"chroma-direct+langchain-agent+semantic-intent:{intent.name}",
            orchestrationStrategy="langchain-agent",
        )




pipeline = AgenticPipeline()
