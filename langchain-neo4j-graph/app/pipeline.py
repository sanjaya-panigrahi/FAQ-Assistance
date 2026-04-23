import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.graphs import Neo4jGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


NO_CONTEXT_ANSWER = (
    "I could not find grounded FAQ evidence for that question. "
    "Please refine the question or ingest more tenant data."
)


class GraphPipeline:
    def __init__(self) -> None:
        self._graph_client = None
        self._llm = None

    def _get_graph_client(self) -> Neo4jGraph | None:
        if self._graph_client is None:
            try:
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
            chroma = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            chroma.heartbeat()
            graph_client = self._get_graph_client()
            count_rows = graph_client.query("MATCH (f:FaqEntry) RETURN count(f) AS total") if graph_client else []
            total = int(count_rows[0]["total"]) if count_rows else 0
            return {"status": "UP", "graphFacts": total, "backend": "chromadb+neo4j"}
        except Exception as exc:
            return {"status": "DEGRADED", "error": str(exc), "backend": "chromadb+neo4j"}

    def rebuild_index(self) -> int:
        docs = parse_faq_documents(settings.faq_source_file)
        rows = [
            {"id": d.metadata["id"], "question": d.metadata["question"], "answer": d.metadata["answer"]}
            for d in docs
        ]
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
        return len(docs)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )
        vector_docs = vector_store.similarity_search(question, k=6)
        vector_context = "\n\n".join(doc.page_content for doc in vector_docs)

        graph_client = self._get_graph_client()
        try:
            rows = graph_client.query(
                """
                CALL db.index.fulltext.queryNodes('faq_fulltext', $q) YIELD node, score
                RETURN node.question AS question, node.answer AS answer, score
                ORDER BY score DESC
                LIMIT 4
                """,
                {"q": question},
            ) if graph_client else []
        except Exception:
            rows = []
        graph_context = "\n\n".join(f"Q: {row['question']}\nA: {row['answer']}" for row in rows)

        if not vector_docs and not rows:
            return RagResponse(
                answer=NO_CONTEXT_ANSWER,
                graphFacts=0,
                strategy="chroma-direct+fallback-no-context",
                orchestrationStrategy="langchain-graph-prototype",
            )

        system_prompt = (
            "You are a FAQ assistant. Answer the user's question using ONLY the provided FAQ context below. "
            "Do NOT use any external knowledge or make assumptions beyond what is stated. "
            "If the context contains a general policy that covers the topic, apply it directly — "
            "do not refuse to answer just because the product is not explicitly named in the policy. "
            "Answer concisely and factually based solely on the context."
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

        return RagResponse(
            answer=str(answer),
            graphFacts=len(rows),
            strategy="chroma-direct+neo4j-graph",
            orchestrationStrategy="langchain-graph-prototype",
        )


pipeline = GraphPipeline()
