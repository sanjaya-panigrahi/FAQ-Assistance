"""Neo4j MCP Server.

Exposes Neo4j knowledge graph operations as MCP tools and resources.
Tools: fulltext_search, traverse_graph, run_cypher_query, get_entity_relationships, rebuild_graph_index
Resources: neo4j://schema, neo4j://stats
"""

import json
import logging
import os

from neo4j import GraphDatabase
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

NEO4J_URI = os.getenv("NEO4J_URI", "bolt://neo4j-unified:7687")
NEO4J_USERNAME = os.getenv("NEO4J_USERNAME", "neo4j")
NEO4J_PASSWORD = os.getenv("NEO4J_PASSWORD", "neo4jpass")

mcp = FastMCP(
    "Neo4j MCP Server",
    description="Knowledge graph operations for FAQ entity relationships via Neo4j",
)

_driver = None


def _get_driver():
    global _driver
    if _driver is None:
        _driver = GraphDatabase.driver(NEO4J_URI, auth=(NEO4J_USERNAME, NEO4J_PASSWORD))
    return _driver


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def fulltext_search(
    query: str,
    index_name: str = "faq_fulltext",
    limit: int = 10,
) -> dict:
    """Search the Neo4j fulltext index for entities matching the query.

    Args:
        query: The search query string (supports Lucene query syntax).
        index_name: Name of the fulltext index to search.
        limit: Maximum number of results to return.

    Returns:
        List of matched nodes with their labels, properties, and relevance scores.
    """
    driver = _get_driver()
    cypher = """
        CALL db.index.fulltext.queryNodes($index_name, $query)
        YIELD node, score
        RETURN node, labels(node) AS labels, score
        ORDER BY score DESC
        LIMIT $limit
    """
    try:
        with driver.session() as session:
            result = session.run(cypher, index_name=index_name, query=query, limit=limit)
            records = []
            for record in result:
                node = record["node"]
                records.append({
                    "labels": record["labels"],
                    "properties": dict(node),
                    "score": record["score"],
                })
            return {"results": records, "count": len(records)}
    except Exception as exc:
        return {"error": str(exc), "results": []}


@mcp.tool()
def traverse_graph(
    entity_name: str,
    max_depth: int = 2,
    relationship_types: list[str] | None = None,
    limit: int = 50,
) -> dict:
    """Traverse the knowledge graph from a starting entity to find related nodes.

    Args:
        entity_name: Name or identifier of the starting entity.
        max_depth: Maximum traversal depth (1-5).
        relationship_types: Optional list of relationship types to follow. If None, follows all.
        limit: Maximum number of paths to return.

    Returns:
        Related entities with their relationships and traversal paths.
    """
    max_depth = max(1, min(5, max_depth))
    driver = _get_driver()

    if relationship_types:
        rel_filter = "|".join(f"`{r}`" for r in relationship_types)
        cypher = f"""
            MATCH (start)-[r:{rel_filter}*1..{max_depth}]-(related)
            WHERE start.name =~ $pattern OR start.title =~ $pattern
            RETURN start, r, related,
                   labels(start) AS start_labels,
                   labels(related) AS related_labels
            LIMIT $limit
        """
    else:
        cypher = f"""
            MATCH (start)-[r*1..{max_depth}]-(related)
            WHERE start.name =~ $pattern OR start.title =~ $pattern
            RETURN start, r, related,
                   labels(start) AS start_labels,
                   labels(related) AS related_labels
            LIMIT $limit
        """

    pattern = f"(?i).*{entity_name}.*"
    try:
        with driver.session() as session:
            result = session.run(cypher, pattern=pattern, limit=limit)
            paths = []
            for record in result:
                start_node = record["start"]
                related_node = record["related"]
                rels = record["r"]
                rel_info = []
                for rel in rels if isinstance(rels, list) else [rels]:
                    rel_info.append({
                        "type": rel.type,
                        "properties": dict(rel),
                    })
                paths.append({
                    "start": {
                        "labels": record["start_labels"],
                        "properties": dict(start_node),
                    },
                    "relationships": rel_info,
                    "related": {
                        "labels": record["related_labels"],
                        "properties": dict(related_node),
                    },
                })
            return {"entity": entity_name, "depth": max_depth, "paths": paths, "count": len(paths)}
    except Exception as exc:
        return {"error": str(exc), "paths": []}


@mcp.tool()
def run_cypher_query(
    cypher: str,
    parameters: str | None = None,
) -> dict:
    """Execute a read-only Cypher query against the Neo4j database.

    Only read operations (MATCH, RETURN, WITH, CALL) are allowed.
    Write operations (CREATE, MERGE, DELETE, SET, REMOVE, DROP) are blocked.

    Args:
        cypher: The Cypher query to execute.
        parameters: Optional JSON string of query parameters.

    Returns:
        Query results as a list of records.
    """
    # Block write operations for security
    cypher_upper = cypher.upper().strip()
    write_keywords = ["CREATE", "MERGE", "DELETE", "DETACH", "SET ", "REMOVE", "DROP", "CALL {"]
    for keyword in write_keywords:
        if keyword in cypher_upper:
            return {"error": f"Write operations are not allowed. Found: {keyword.strip()}"}

    params = json.loads(parameters) if parameters else {}
    driver = _get_driver()
    try:
        with driver.session() as session:
            result = session.run(cypher, **params)
            records = [dict(record) for record in result]
            # Convert neo4j types to serializable format
            serialized = []
            for record in records:
                row = {}
                for key, value in record.items():
                    if hasattr(value, "__iter__") and not isinstance(value, (str, dict)):
                        row[key] = list(value)
                    elif hasattr(value, "items"):
                        row[key] = dict(value)
                    else:
                        row[key] = value
                serialized.append(row)
            return {"results": serialized, "count": len(serialized)}
    except Exception as exc:
        return {"error": str(exc), "results": []}


@mcp.tool()
def get_entity_relationships(
    entity_name: str,
    direction: str = "both",
) -> dict:
    """Get all direct relationships for a named entity.

    Args:
        entity_name: Name of the entity to look up.
        direction: Relationship direction - 'incoming', 'outgoing', or 'both'.

    Returns:
        List of relationships with connected entity details.
    """
    driver = _get_driver()
    if direction == "outgoing":
        cypher = """
            MATCH (e)-[r]->(target)
            WHERE e.name =~ $pattern OR e.title =~ $pattern
            RETURN type(r) AS rel_type, properties(r) AS rel_props,
                   labels(target) AS target_labels, properties(target) AS target_props
            LIMIT 50
        """
    elif direction == "incoming":
        cypher = """
            MATCH (source)-[r]->(e)
            WHERE e.name =~ $pattern OR e.title =~ $pattern
            RETURN type(r) AS rel_type, properties(r) AS rel_props,
                   labels(source) AS source_labels, properties(source) AS source_props
            LIMIT 50
        """
    else:
        cypher = """
            MATCH (e)-[r]-(connected)
            WHERE e.name =~ $pattern OR e.title =~ $pattern
            RETURN type(r) AS rel_type, properties(r) AS rel_props,
                   labels(connected) AS connected_labels, properties(connected) AS connected_props
            LIMIT 50
        """

    pattern = f"(?i).*{entity_name}.*"
    try:
        with driver.session() as session:
            result = session.run(cypher, pattern=pattern)
            relationships = []
            for record in result:
                rel = {"type": record["rel_type"], "properties": dict(record["rel_props"])}
                connected_key = "target_labels" if "target_labels" in record.keys() else (
                    "source_labels" if "source_labels" in record.keys() else "connected_labels"
                )
                props_key = connected_key.replace("_labels", "_props")
                rel["connected"] = {
                    "labels": record[connected_key],
                    "properties": dict(record[props_key]),
                }
                relationships.append(rel)
            return {"entity": entity_name, "relationships": relationships, "count": len(relationships)}
    except Exception as exc:
        return {"error": str(exc), "relationships": []}


@mcp.tool()
def rebuild_graph_index(
    index_name: str = "faq_fulltext",
    node_labels: list[str] | None = None,
    properties: list[str] | None = None,
) -> dict:
    """Rebuild the Neo4j fulltext index for FAQ entities.

    Args:
        index_name: Name of the fulltext index to create/rebuild.
        node_labels: Node labels to index (defaults to ['FAQ', 'Section', 'Category']).
        properties: Node properties to index (defaults to ['name', 'title', 'content']).

    Returns:
        Status of the index rebuild operation.
    """
    if node_labels is None:
        node_labels = ["FAQ", "Section", "Category"]
    if properties is None:
        properties = ["name", "title", "content"]

    driver = _get_driver()
    # Validate label and property names (alphanumeric + underscore only)
    import re
    for label in node_labels:
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", label):
            return {"error": f"Invalid label name: {label}"}
    for prop in properties:
        if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*$", prop):
            return {"error": f"Invalid property name: {prop}"}

    labels_str = "|".join(node_labels)
    props_str = ", ".join(f"n.{p}" for p in properties)

    try:
        with driver.session() as session:
            # Drop existing index if present
            try:
                session.run(f"DROP INDEX {index_name} IF EXISTS")
            except Exception:
                pass

            cypher = f"""
                CREATE FULLTEXT INDEX {index_name}
                FOR (n:{labels_str})
                ON EACH [{props_str}]
            """
            session.run(cypher)
            return {"status": "ok", "index": index_name, "labels": node_labels, "properties": properties}
    except Exception as exc:
        return {"error": str(exc)}


# ─── Resources ───────────────────────────────────────────────────────────────


@mcp.resource("neo4j://schema")
def get_schema_resource() -> str:
    """Get the Neo4j database schema (node labels, relationship types, properties)."""
    driver = _get_driver()
    try:
        with driver.session() as session:
            # Node labels
            labels = [r["label"] for r in session.run("CALL db.labels() YIELD label RETURN label")]
            # Relationship types
            rel_types = [r["relationshipType"] for r in session.run(
                "CALL db.relationshipTypes() YIELD relationshipType RETURN relationshipType"
            )]
            # Property keys
            prop_keys = [r["propertyKey"] for r in session.run(
                "CALL db.propertyKeys() YIELD propertyKey RETURN propertyKey"
            )]
            schema = {
                "node_labels": labels,
                "relationship_types": rel_types,
                "property_keys": prop_keys,
            }
    except Exception as exc:
        schema = {"error": str(exc)}
    return json.dumps(schema, indent=2)


@mcp.resource("neo4j://stats")
def get_stats_resource() -> str:
    """Get Neo4j database statistics (node counts, relationship counts)."""
    driver = _get_driver()
    try:
        with driver.session() as session:
            node_count = session.run("MATCH (n) RETURN count(n) AS count").single()["count"]
            rel_count = session.run("MATCH ()-[r]->() RETURN count(r) AS count").single()["count"]
            stats = {"total_nodes": node_count, "total_relationships": rel_count}
    except Exception as exc:
        stats = {"error": str(exc)}
    return json.dumps(stats, indent=2)


@mcp.tool()
def health_check() -> dict:
    """Check Neo4j connectivity and health.

    Returns:
        Health status with server info.
    """
    try:
        driver = _get_driver()
        driver.verify_connectivity()
        info = driver.get_server_info()
        return {
            "status": "UP",
            "backend": "neo4j",
            "address": str(info.address),
            "protocol_version": str(info.protocol_version),
        }
    except Exception as exc:
        return {"status": "DOWN", "backend": "neo4j", "error": str(exc)}


if __name__ == "__main__":
    mcp.run(transport="sse")
