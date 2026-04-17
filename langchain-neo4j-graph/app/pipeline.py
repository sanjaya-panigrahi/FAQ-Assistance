from langchain_openai import ChatOpenAI
from langchain_community.graphs import Neo4jGraph

from .config import settings
from .faq_parser import parse_faq_documents
from .schemas import RagResponse


class GraphPipeline:
    def graph_client(self) -> Neo4jGraph:
        return Neo4jGraph(url=settings.neo4j_uri, username=settings.neo4j_username, password=settings.neo4j_password)

    def health(self) -> dict:
        try:
            graph = self.graph_client()
            count_rows = graph.query("MATCH (f:FaqEntry) RETURN count(f) AS total")
            total = int(count_rows[0]["total"]) if count_rows else 0
            return {"status": "UP", "graphFacts": total}
        except Exception as exc:  # pragma: no cover
            return {"status": "DOWN", "error": str(exc)}

    def rebuild_index(self) -> int:
        graph = self.graph_client()
        docs = parse_faq_documents(settings.faq_source_file)

        graph.query("CREATE CONSTRAINT faq_id_unique IF NOT EXISTS FOR (f:FaqEntry) REQUIRE f.id IS UNIQUE")
        graph.query("CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]")
        graph.query("MATCH (f:FaqEntry) DETACH DELETE f")

        batch = [
            {
                "id": doc.metadata["id"],
                "question": doc.metadata["question"],
                "answer": doc.metadata["answer"],
            }
            for doc in docs
        ]

        graph.query(
            """
            UNWIND $rows AS row
            CREATE (f:FaqEntry {id: row.id, question: row.question, answer: row.answer})
            """,
            {"rows": batch},
        )

        return len(docs)

    def ask(self, question: str) -> RagResponse:
        graph = self.graph_client()
        rows = graph.query(
            """
            CALL db.index.fulltext.queryNodes('faq_fulltext', $q) YIELD node, score
            RETURN node.id AS id, node.question AS question, node.answer AS answer, score
            ORDER BY score DESC
            LIMIT 5
            """,
            {"q": question},
        )
        context = "\n\n".join(f"Q: {row['question']}\nA: {row['answer']}" for row in rows)

        llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        answer = llm.invoke(
            (
                "You are a graph-retrieval FAQ assistant. Use the graph context first and answer precisely.\n\n"
                f"Question: {question}\n\n"
                f"Graph Context:\n{context or 'No matching graph facts found.'}"
            )
        ).content

        return RagResponse(answer=str(answer), graphFacts=len(rows), strategy="neo4j-fulltext-prototype")


pipeline = GraphPipeline()
