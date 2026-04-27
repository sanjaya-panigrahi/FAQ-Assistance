"""ChromaDB MCP Server.

Exposes ChromaDB vector store operations as MCP tools and resources.
Tools: similarity_search, hybrid_search, upsert_documents, delete_documents, list_collections
Resources: chroma://collections, chroma://collections/{tenant}/stats
"""

import json
import logging
import os

import chromadb
from mcp.server.fastmcp import FastMCP

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger(__name__)

CHROMA_HOST = os.getenv("CHROMA_HOST", "chroma-faq")
CHROMA_PORT = int(os.getenv("CHROMA_PORT", "8000"))
COLLECTION_PREFIX = os.getenv("APP_CHROMA_COLLECTION_NAME_PREFIX", "faq_")

mcp = FastMCP(
    "ChromaDB MCP Server",
    description="Vector store operations for FAQ embeddings via ChromaDB",
)

_client: chromadb.HttpClient | None = None


def _get_client() -> chromadb.HttpClient:
    global _client
    if _client is None:
        _client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
    return _client


# ─── Tools ───────────────────────────────────────────────────────────────────


@mcp.tool()
def similarity_search(
    query_texts: list[str],
    tenant_id: str = "default",
    top_k: int = 6,
    include_metadata: bool = True,
) -> dict:
    """Search ChromaDB for documents similar to the query texts.

    Args:
        query_texts: One or more query strings to search for.
        tenant_id: Tenant identifier to scope the search (maps to a ChromaDB collection).
        top_k: Number of top results to return per query.
        include_metadata: Whether to include document metadata in results.

    Returns:
        Dictionary with 'results' containing matched documents, distances, and metadata.
    """
    client = _get_client()
    collection_name = f"{COLLECTION_PREFIX}{tenant_id}"
    try:
        collection = client.get_collection(name=collection_name)
    except Exception:
        return {"error": f"Collection '{collection_name}' not found", "results": []}

    include = ["documents", "distances"]
    if include_metadata:
        include.append("metadatas")

    results = collection.query(
        query_texts=query_texts,
        n_results=top_k,
        include=include,
    )
    return {
        "collection": collection_name,
        "results": {
            "ids": results.get("ids", []),
            "documents": results.get("documents", []),
            "distances": results.get("distances", []),
            "metadatas": results.get("metadatas", []) if include_metadata else [],
        },
    }


@mcp.tool()
def hybrid_search(
    query_text: str,
    tenant_id: str = "default",
    top_k: int = 6,
    where_filter: str | None = None,
) -> dict:
    """Perform a hybrid search combining vector similarity with optional metadata filters.

    Args:
        query_text: The query string to search for.
        tenant_id: Tenant identifier to scope the search.
        top_k: Number of top results to return.
        where_filter: Optional JSON string for ChromaDB where-filter (e.g. '{"source": "faq.md"}').

    Returns:
        Dictionary with matched documents, scores, and metadata.
    """
    client = _get_client()
    collection_name = f"{COLLECTION_PREFIX}{tenant_id}"
    try:
        collection = client.get_collection(name=collection_name)
    except Exception:
        return {"error": f"Collection '{collection_name}' not found", "results": []}

    kwargs = {
        "query_texts": [query_text],
        "n_results": top_k,
        "include": ["documents", "distances", "metadatas"],
    }
    if where_filter:
        kwargs["where"] = json.loads(where_filter)

    results = collection.query(**kwargs)
    return {
        "collection": collection_name,
        "results": {
            "ids": results.get("ids", []),
            "documents": results.get("documents", []),
            "distances": results.get("distances", []),
            "metadatas": results.get("metadatas", []),
        },
    }


@mcp.tool()
def upsert_documents(
    tenant_id: str,
    documents: list[str],
    ids: list[str],
    metadatas: list[dict] | None = None,
) -> dict:
    """Upsert documents into a ChromaDB collection.

    Args:
        tenant_id: Tenant identifier for the target collection.
        documents: List of document text contents.
        ids: List of unique IDs for each document (must match documents length).
        metadatas: Optional list of metadata dicts for each document.

    Returns:
        Confirmation with count of upserted documents.
    """
    if len(documents) != len(ids):
        return {"error": "documents and ids must have the same length"}

    client = _get_client()
    collection_name = f"{COLLECTION_PREFIX}{tenant_id}"
    collection = client.get_or_create_collection(name=collection_name)

    kwargs = {"ids": ids, "documents": documents}
    if metadatas:
        kwargs["metadatas"] = metadatas

    collection.upsert(**kwargs)
    return {"status": "ok", "collection": collection_name, "upserted": len(documents)}


@mcp.tool()
def delete_documents(
    tenant_id: str,
    ids: list[str],
) -> dict:
    """Delete documents from a ChromaDB collection by their IDs.

    Args:
        tenant_id: Tenant identifier for the target collection.
        ids: List of document IDs to delete.

    Returns:
        Confirmation with count of deleted documents.
    """
    client = _get_client()
    collection_name = f"{COLLECTION_PREFIX}{tenant_id}"
    try:
        collection = client.get_collection(name=collection_name)
    except Exception:
        return {"error": f"Collection '{collection_name}' not found"}

    collection.delete(ids=ids)
    return {"status": "ok", "collection": collection_name, "deleted": len(ids)}


@mcp.tool()
def list_collections() -> dict:
    """List all ChromaDB collections with their document counts.

    Returns:
        Dictionary with list of collections and their sizes.
    """
    client = _get_client()
    collections = client.list_collections()
    result = []
    for col in collections:
        try:
            c = client.get_collection(name=col.name)
            result.append({"name": col.name, "count": c.count()})
        except Exception:
            result.append({"name": col.name, "count": -1})
    return {"collections": result}


# ─── Resources ───────────────────────────────────────────────────────────────


@mcp.resource("chroma://collections")
def get_collections_resource() -> str:
    """List all ChromaDB collections as a JSON resource."""
    data = list_collections()
    return json.dumps(data, indent=2)


@mcp.resource("chroma://collections/{tenant_id}/stats")
def get_collection_stats(tenant_id: str) -> str:
    """Get statistics for a specific tenant's ChromaDB collection."""
    client = _get_client()
    collection_name = f"{COLLECTION_PREFIX}{tenant_id}"
    try:
        collection = client.get_collection(name=collection_name)
        stats = {
            "collection": collection_name,
            "tenant_id": tenant_id,
            "document_count": collection.count(),
            "metadata": collection.metadata or {},
        }
    except Exception as exc:
        stats = {
            "collection": collection_name,
            "tenant_id": tenant_id,
            "error": str(exc),
        }
    return json.dumps(stats, indent=2)


# ─── Health ──────────────────────────────────────────────────────────────────


@mcp.tool()
def health_check() -> dict:
    """Check ChromaDB connectivity and health.

    Returns:
        Health status with heartbeat result.
    """
    try:
        client = _get_client()
        heartbeat = client.heartbeat()
        return {"status": "UP", "backend": "chromadb", "heartbeat": heartbeat}
    except Exception as exc:
        return {"status": "DOWN", "backend": "chromadb", "error": str(exc)}


if __name__ == "__main__":
    mcp.run(transport="sse")
