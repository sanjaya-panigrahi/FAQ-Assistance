from typing import TypedDict

from langchain_openai import ChatOpenAI
from langgraph.graph import END, StateGraph
from neo4j import GraphDatabase

from .config import settings
from .faq_parser import parse_faq_entries
from .schemas import RagResponse


class GraphState(TypedDict, total=False):
    question: str
    traversal_plan: str
    rows: list[dict]
    answer: str


class GraphPipeline:
    def __init__(self) -> None:
        self._driver_instance = GraphDatabase.driver(
            settings.neo4j_uri,
            auth=(settings.neo4j_username, settings.neo4j_password),
        )
        self._llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)
        self._graph = self._build_graph()

    def health(self) -> dict:
        try:
            records, _, _ = self._driver_instance.execute_query("MATCH (f:FaqEntry) RETURN count(f) AS total")
            total = int(records[0]["total"]) if records else 0
            return {"status": "UP", "graphFacts": total}
        except Exception as exc:  # pragma: no cover
            return {"status": "DOWN", "error": str(exc)}

    def rebuild_index(self) -> int:
        rows = parse_faq_entries(settings.faq_source_file)
        current_ids = [r["id"] for r in rows]
        self._driver_instance.execute_query("CREATE CONSTRAINT faq_id_unique IF NOT EXISTS FOR (f:FaqEntry) REQUIRE f.id IS UNIQUE")
        self._driver_instance.execute_query("CREATE FULLTEXT INDEX faq_fulltext IF NOT EXISTS FOR (f:FaqEntry) ON EACH [f.question, f.answer]")
        self._driver_instance.execute_query(
            """
            UNWIND $rows AS row
            MERGE (f:FaqEntry {id: row.id})
            SET f.question = row.question, f.answer = row.answer
            """,
            rows=rows,
        )
        self._driver_instance.execute_query(
            "MATCH (f:FaqEntry) WHERE NOT f.id IN $ids DETACH DELETE f",
            ids=current_ids,
        )
        return len(rows)

    def ask(self, question: str) -> RagResponse:
        final_state = self._graph.invoke({"question": question})
        rows = final_state.get("rows", [])
        return RagResponse(
            answer=final_state.get("answer", "No answer generated."),
            graphFacts=len(rows),
            strategy="langgraph-graph-workflow",
        )

    def _build_graph(self):
        graph = StateGraph(GraphState)
        graph.add_node("plan_step", self._plan_node)
        graph.add_node("traverse_step", self._traverse_node)
        graph.add_node("answer_step", self._answer_node)

        graph.set_entry_point("plan_step")
        graph.add_edge("plan_step", "traverse_step")
        graph.add_edge("traverse_step", "answer_step")
        graph.add_edge("answer_step", END)
        return graph.compile()

    def _plan_node(self, state: GraphState) -> GraphState:
        question = state["question"]
        return {"traversal_plan": f"Use fulltext traversal for question: {question}"}

    def _traverse_node(self, state: GraphState) -> GraphState:
        records, _, _ = self._driver_instance.execute_query(
            """
            CALL db.index.fulltext.queryNodes('faq_fulltext', $q) YIELD node, score
            RETURN node.id AS id, node.question AS question, node.answer AS answer, score
            ORDER BY score DESC
            LIMIT 5
            """,
            q=state["question"],
        )
        rows = [dict(record) for record in records]
        return {"rows": rows}

    def _answer_node(self, state: GraphState) -> GraphState:
        context = "\n\n".join(f"Q: {row['question']}\nA: {row['answer']}" for row in state.get("rows", []))
        answer = self._llm.invoke(
            (
                "You are a graph-traversal FAQ assistant. Graph traversal is one step of a broader workflow. "
                "Answer precisely from graph context.\n\n"
                f"Traversal Plan: {state.get('traversal_plan', '')}\n"
                f"Question: {state['question']}\n\n"
                f"Graph Context:\n{context or 'No graph facts found.'}"
            )
        ).content
        return {"answer": str(answer)}


pipeline = GraphPipeline()
