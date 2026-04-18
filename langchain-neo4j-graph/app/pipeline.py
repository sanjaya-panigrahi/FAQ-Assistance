from langchain_openai import ChatOpenAI
from langchain_community.graphs import Neo4jGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class GraphPipeline:
    def __init__(self) -> None:
        self._graph_client = Neo4jGraph(
            url=settings.neo4j_uri,
            username=settings.neo4j_username,
            password=settings.neo4j_password,
        )
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)

    def health(self) -> dict:
        try:
            count_rows = self._graph_client.query("MATCH (f:FaqEntry) RETURN count(f) AS total")
            total = int(count_rows[0]["total"]) if count_rows else 0
            return {"status": "UP", "graphFacts": total}
        except Exception as exc:  # pragma: no cover
            return {"status": "DOWN", "error": str(exc)}

    def rebuild_index(self) -> int:
        docs = parse_faq_documents(settings.faq_source_file)
        batch = [
            {
                "id": doc.metadata["id"],
                "question": doc.metadata["question"],
                "answer": doc.metadata["answer"],
            }
            for doc in docs
        ]
        current_ids = [doc.metadata["id"] for doc in docs]

        self._graph_client.query("CREATE CONSTRAINT faq_id_unique IF NOT EXISTS FOR (f:FaqEntry) REQUIRE f.id IS UNIQUE")
        self._graph_client.query("CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]")
        self._graph_client.query(
            """
            UNWIND $rows AS row
            MERGE (f:FaqEntry {id: row.id})
            SET f.question = row.question, f.answer = row.answer
            """,
            {"rows": batch},
        )
        self._graph_client.query(
            "MATCH (f:FaqEntry) WHERE NOT f.id IN $ids DETACH DELETE f",
            {"ids": current_ids},
        )

        return len(docs)

    def ask(self, question: str) -> RagResponse:
        rows = self._graph_client.query(
            """
            CALL db.index.fulltext.queryNodes('faq_fulltext', $q) YIELD node, score
            RETURN node.id AS id, node.question AS question, node.answer AS answer, score
            ORDER BY score DESC
            LIMIT 5
            """,
            {"q": question},
        )
        context = "\n\n".join(f"Q: {row['question']}\nA: {row['answer']}" for row in rows)

        answer = self._llm.invoke(
            (
                "You are a graph-retrieval FAQ assistant. Use the graph context first and answer precisely.\n\n"
                f"Question: {question}\n\n"
                f"Graph Context:\n{context or 'No matching graph facts found.'}"
            )
        ).content

        return RagResponse(answer=str(answer), graphFacts=len(rows), strategy="neo4j-fulltext-prototype")


pipeline = GraphPipeline()
