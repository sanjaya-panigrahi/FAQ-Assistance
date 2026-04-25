import time

import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.graphs import Neo4jGraph

from .analytics_client import post_analytics_event
from .config import settings
from .faq_parser import parse_faq_entries
from .schemas import RagResponse


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class GraphPipeline:
    def __init__(self) -> None:
        self._graph_client = None
        self._llm = None
        self._chroma_client = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
        self._embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        self._warmup()

    def _warmup(self) -> None:
        try:
            self._embeddings.embed_query("warmup")
        except Exception:
            pass

    def _get_graph_client(self) -> Neo4jGraph | None:
        if self._graph_client is None:
            try:
                # Avoid startup failure when APOC metadata procedures are unavailable.
                self._graph_client = Neo4jGraph(
                    url=settings.neo4j_uri,
                    username=settings.neo4j_username,
                    password=settings.neo4j_password,
                    refresh_schema=False,
                )
            except Exception:
                self._graph_client = None
        return self._graph_client

    def close(self) -> None:
        """Close underlying graph driver to avoid destructor warnings."""
        client = self._graph_client
        if client is None:
            return
        driver = getattr(client, "_driver", None)
        if driver is not None:
            try:
                driver.close()
            except Exception:
                pass
        self._graph_client = None

    def _get_llm(self) -> ChatOpenAI:
        if self._llm is None:
            self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        return self._llm

    def health(self) -> dict:
        try:
            self._chroma_client.heartbeat()
            total = 0
            graph_client = self._get_graph_client()
            if graph_client is not None:
                count_rows = graph_client.query("MATCH (f:FaqEntry) RETURN count(f) AS total")
                total = int(count_rows[0]["total"]) if count_rows else 0
            return {"status": "UP", "graphFacts": total, "backend": "chromadb+neo4j"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb+neo4j", "error": str(exc)}

    def rebuild_index(self) -> int:
        rows = parse_faq_entries(settings.faq_source_file)
        graph_client = self._get_graph_client()
        if graph_client is None:
            return 0
        graph_client.query("MATCH (f:FaqEntry) DETACH DELETE f")
        graph_client.query(
            "UNWIND $rows AS row CREATE (f:FaqEntry {id: row.id, question: row.question, answer: row.answer})",
            {"rows": rows},
        )
        try:
            graph_client.query(
                "CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]"
            )
        except Exception as exc:
            message = str(exc)
            if "EquivalentSchemaRuleAlreadyExists" not in message and "equivalent index already exists" not in message.lower():
                raise
        return len(rows)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = self._embeddings
        vector_store = Chroma(
            client=self._chroma_client,
            collection_name=collection,
            embedding_function=embeddings,
        )
        vector_docs = vector_store.similarity_search(question, k=6)
        vector_context = "\n\n".join(doc.page_content for doc in vector_docs)

        rows = []
        graph_client = self._get_graph_client()
        if graph_client is not None:
            try:
                rows = graph_client.query(
                    """
                    CALL db.index.fulltext.queryNodes('faq_fulltext', $q) YIELD node, score
                    RETURN node.question AS question, node.answer AS answer, score
                    ORDER BY score DESC
                    LIMIT 4
                    """,
                    {"q": question},
                )
            except Exception:
                rows = []
        graph_context = "\n\n".join(f"Q: {row['question']}\nA: {row['answer']}" for row in rows)

        if not vector_docs and not rows:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                graphFacts=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langgraph-graph-workflow",
            )

        system_prompt = (
            "You are a FAQ assistant. Answer the user's question using ONLY the provided FAQ context below. "
            "Answer concisely and factually."
        )
        user_prompt = (
            f"Question: {question}\n\n"
            f"FAQ Vector Context:\n{vector_context or 'None'}\n\n"
            f"FAQ Graph Context:\n{graph_context or 'None'}"
        )
        from langchain_core.messages import SystemMessage, HumanMessage
        answer = self._get_llm().invoke(
            [SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)]
        ).content

        response = RagResponse(
            answer=str(answer),
            graphFacts=len(rows),
            strategy="chroma-direct+neo4j-graph",
            orchestrationStrategy="langgraph-graph-workflow",
        )
        post_analytics_event(
            question=question, response_text=response.answer,
            customer_id=customer_id or "default", rag_pattern="neo4j-graph",
            framework="langgraph", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000),
            context_docs=f"{vector_context}\n\n{graph_context}".strip(),
        )
        return response


pipeline = GraphPipeline()
