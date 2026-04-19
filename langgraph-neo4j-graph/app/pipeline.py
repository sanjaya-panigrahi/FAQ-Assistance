import chromadb

from langchain_chroma import Chroma
from langchain_openai import ChatOpenAI, OpenAIEmbeddings
from langchain_community.graphs import Neo4jGraph

from .config import settings
from .faq_parser import parse_faq_entries
from .schemas import RagResponse


class GraphPipeline:
    def __init__(self) -> None:
        self._graph_client = None
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
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)

    def health(self) -> dict:
        try:
            chroma = chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port)
            chroma.heartbeat()
            total = 0
            if self._graph_client is not None:
                count_rows = self._graph_client.query("MATCH (f:FaqEntry) RETURN count(f) AS total")
                total = int(count_rows[0]["total"]) if count_rows else 0
            return {"status": "UP", "graphFacts": total, "backend": "chromadb+neo4j"}
        except Exception as exc:
            return {"status": "DEGRADED", "backend": "chromadb+neo4j", "error": str(exc)}

    def rebuild_index(self) -> int:
        rows = parse_faq_entries(settings.faq_source_file)
        if self._graph_client is None:
            return 0
        self._graph_client.query("MATCH (f:FaqEntry) DETACH DELETE f")
        self._graph_client.query(
            "UNWIND $rows AS row CREATE (f:FaqEntry {id: row.id, question: row.question, answer: row.answer})",
            {"rows": rows},
        )
        self._graph_client.query(
            "CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]"
        )
        return len(rows)

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        tenant = (customer_id or "default").strip()
        collection = f"{settings.chroma_collection_prefix}{tenant}"

        embeddings = OpenAIEmbeddings(model=settings.openai_embedding_model)
        vector_store = Chroma(
            client=chromadb.HttpClient(host=settings.chroma_host, port=settings.chroma_port),
            collection_name=collection,
            embedding_function=embeddings,
        )
        vector_docs = vector_store.similarity_search(question, k=4)
        vector_context = "\n\n".join(doc.page_content for doc in vector_docs)

        rows = []
        if self._graph_client is not None:
            try:
                rows = self._graph_client.query(
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

        answer = self._llm.invoke(
            (
                "You are a graph-retrieval FAQ assistant. Use both vector context and graph context.\n\n"
                f"Question: {question}\n\n"
                f"Vector Context:\n{vector_context or 'No vector matches found.'}\n\n"
                f"Graph Context:\n{graph_context or 'No graph facts found.'}"
            )
        ).content

        return RagResponse(
            answer=str(answer),
            graphFacts=len(rows),
            strategy="chroma-direct+langgraph-graph",
            orchestrationStrategy="langgraph-graph-workflow",
        )


pipeline = GraphPipeline()
