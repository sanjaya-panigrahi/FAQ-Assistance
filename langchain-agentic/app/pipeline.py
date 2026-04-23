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
    if intent_name == "payment" or ("payment" in q or "pay" in q or "emi" in q or "installment" in q or "cod" in q):
        extras.append("payment modes payment options EMI installment cash on delivery store credit")
    return " ".join([question, *extras]).strip()


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
            top_k = 8 if intent.name == "product_availability" else 6
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

        customer_label = (customer_id or "the company").strip()
        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)

        # Direct LLM generation with pre-retrieved context (no agent loop needed)
        prompt = ChatPromptTemplate.from_messages(
            [
                (
                    "system",
                    f"You are a support assistant for {customer_label}. "
                    "Answer the question using ONLY the FAQ context provided below. "
                    "If the context contains a general policy (e.g. return policy, warranty), apply it directly to the specific product the user asks about. "
                    "Do not say the information is missing if a general policy covers it. "
                    "Do not invent facts or add caveats not present in the context.",
                ),
                (
                    "human",
                    f"Question: {question}\n\nFAQ Context:\n{combined_context}\n\nProvide a concise, grounded answer.",
                ),
            ]
        )
        result = llm.invoke(prompt.format_messages())
        answer = str(result.content).strip()

        # If the direct answer is a refusal, fall back to agent with tools
        if not answer or "do not know" in answer.lower() or "not available" in answer.lower() or "not detailed" in answer.lower():
            retriever_tool = create_retriever_tool(
                retriever,
                "faq_retriever",
                f"Use this tool to retrieve additional FAQ context from {customer_label} knowledge base.",
            )
            agent_prompt = ChatPromptTemplate.from_messages(
                [
                    (
                        "system",
                        f"You are a support assistant for {customer_label}. "
                        "Use the faq_retriever tool to find relevant FAQ context, then answer. "
                        "If a general policy applies to the asked product type, apply it directly. "
                        "Do not add caveats not in the context.",
                    ),
                    ("human", "{input}"),
                    MessagesPlaceholder(variable_name="agent_scratchpad"),
                ]
            )
            agent = create_openai_tools_agent(llm, [retriever_tool, extract_faq_answer], agent_prompt)
            executor = AgentExecutor(
                agent=agent,
                tools=[retriever_tool, extract_faq_answer],
                verbose=False,
                max_iterations=3,
                max_execution_time=20,
                handle_parsing_errors=True,
                early_stopping_method="generate",
            )
            agent_result = executor.invoke({"input": question})
            answer = str(agent_result.get("output", "No answer returned."))

        return RagResponse(
            answer=answer,
            chunksUsed=len(docs),
            strategy=f"chroma-direct+langchain-llm+semantic-intent:{intent.name}",
            orchestrationStrategy="langchain-agent",
        )




pipeline = AgenticPipeline()
