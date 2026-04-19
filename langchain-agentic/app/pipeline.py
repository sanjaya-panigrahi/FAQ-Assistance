import chromadb

from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain.tools.retriever import create_retriever_tool
from langchain_chroma import Chroma
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_openai import ChatOpenAI, OpenAIEmbeddings

from .config import settings
from .schemas import RagResponse


class AgenticPipeline:
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
            vector_store = Chroma(
                client=client,
                collection_name=collection_name,
                embedding_function=embeddings,
            )
            retriever = vector_store.as_retriever(search_kwargs={"k": 4})
        except Exception as exc:
            raise RuntimeError(f"ChromaDB connection failed for collection {collection_name}") from exc

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
                    "You are a MyTechStore support assistant. Always consult tools first for policy questions and answer concisely.",
                ),
                ("human", "{input}"),
                MessagesPlaceholder(variable_name="agent_scratchpad"),
            ]
        )

        agent = create_openai_tools_agent(llm, [retriever_tool], prompt)
        executor = AgentExecutor(agent=agent, tools=[retriever_tool], verbose=False)
        result = executor.invoke({"input": question})
        docs = retriever.invoke(question)

        return RagResponse(
            answer=str(result.get("output", "No answer returned.")),
            chunksUsed=len(docs),
            strategy="chroma-direct+langchain-agent",
            orchestrationStrategy="langchain-agent",
        )


pipeline = AgenticPipeline()
