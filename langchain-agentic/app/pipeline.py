import threading

from langchain.agents import AgentExecutor, create_openai_tools_agent
from langchain.tools.retriever import create_retriever_tool
from langchain_core.prompts import ChatPromptTemplate, MessagesPlaceholder
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.vectorstores import FAISS

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class AgenticPipeline:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._vector_store: FAISS | None = None

    def health(self) -> dict:
        return {"status": "UP", "indexed": self._vector_store is not None}

    def ensure_index(self) -> None:
        if self._vector_store is None:
            self.rebuild_index()

    def rebuild_index(self) -> int:
        documents = parse_faq_documents(settings.faq_source_file)
        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = FAISS.from_documents(documents, embeddings)

        with self._lock:
            self._vector_store = vector_store

        return len(documents)

    def ask(self, question: str) -> RagResponse:
        self.ensure_index()
        if self._vector_store is None:
            raise RuntimeError("Vector store is not initialized")

        retriever = self._vector_store.as_retriever(search_kwargs={"k": 4})
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

        return RagResponse(
            answer=str(result.get("output", "No answer returned.")),
            chunksUsed=4,
            strategy="langchain-agent",
        )


pipeline = AgenticPipeline()
