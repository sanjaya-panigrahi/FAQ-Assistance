"""MCP-backed pipeline implementations.

When USE_MCP=true, these pipeline classes route through MCP servers
instead of using direct ChromaDB/Neo4j/OpenAI/Redis clients.

Each pipeline mirrors the interface of its direct counterpart in app/pipelines/
so routers can swap them transparently via the feature flag.
"""

import logging
import re
import time

from ..mcp_client import McpToolProvider, get_mcp_provider
from ..schemas import (
    RagResponse,
    GraphRagResponse,
    RetrievalQueryResponse,
    RetrievedChunk,
)

logger = logging.getLogger(__name__)


class McpRetrievalPipeline:
    """Retrieval pipeline that uses MCP servers for all backend operations."""

    def __init__(self):
        self._provider: McpToolProvider | None = None

    @property
    def provider(self) -> McpToolProvider:
        if self._provider is None:
            self._provider = get_mcp_provider()
        return self._provider

    def health(self) -> dict:
        result = self.provider.chromadb.call_tool("health_check")
        return result.data if result.success else {"status": "DEGRADED", "error": result.error}

    def rebuild_index(self) -> int:
        return 0

    def query(self, request) -> RetrievalQueryResponse:
        tenant_id = request.tenantId
        question = request.question.strip()

        # Check cache via MCP
        cached = self.provider.cache_get_response("retrieval", tenant_id, question)
        if cached.success and cached.data.get("found"):
            return RetrievalQueryResponse.model_validate(cached.data["value"])

        retrieval_start = time.perf_counter()

        # Transform query
        transformed_query = question
        if request.queryContext:
            transformed_query = f"{question} {request.queryContext.strip()}"

        # Search via MCP ChromaDB
        search_result = self.provider.similarity_search(
            transformed_query, tenant_id, top_k=max(request.topK * 2, 6)
        )

        if not search_result.success:
            return RetrievalQueryResponse(
                tenantId=tenant_id, question=question,
                transformedQuery=transformed_query,
                strategy="mcp+retrieval-failed",
                answer="Search failed. Please try again.",
                chunksUsed=0, grounded=False,
                retrievalLatencyMs=0, generationLatencyMs=0, chunks=[],
            )

        # Process search results
        results = search_result.data.get("results", {})
        documents = results.get("documents", [[]])[0]
        distances = results.get("distances", [[]])[0]
        metadatas = results.get("metadatas", [[]])[0]

        if not documents:
            return RetrievalQueryResponse(
                tenantId=tenant_id, question=question,
                transformedQuery=transformed_query,
                strategy="mcp+no-results",
                answer="No documents found for this tenant.",
                chunksUsed=0, grounded=False,
                retrievalLatencyMs=int((time.perf_counter() - retrieval_start) * 1000),
                generationLatencyMs=0, chunks=[],
            )

        # Build ranked chunks with hybrid scoring
        query_tokens = set(re.findall(r"[a-z0-9]+", transformed_query.lower()))
        ranked = []
        for i, (doc, dist, meta) in enumerate(zip(documents, distances, metadatas)):
            vector_score = max(0, 1 - dist)  # Convert distance to similarity
            chunk_tokens = set(re.findall(r"[a-z0-9]+", doc.lower()))
            overlap = query_tokens & chunk_tokens
            lexical_score = len(overlap) / max(len(query_tokens), 1)
            rerank_score = 0.7 * vector_score + 0.3 * lexical_score
            ranked.append({
                "content": doc,
                "source": meta.get("source", "unknown") if meta else "unknown",
                "chunk_number": meta.get("chunk_number") if meta else None,
                "rerank_score": rerank_score,
            })

        ranked.sort(key=lambda x: x["rerank_score"], reverse=True)
        ranked = ranked[:request.topK]
        retrieval_latency_ms = int((time.perf_counter() - retrieval_start) * 1000)

        # Generate answer via MCP LLM Gateway
        generation_start = time.perf_counter()
        context_lines = [f"[{i+1}] ({c['source']}) {c['content']}" for i, c in enumerate(ranked)]
        context = "\n\n".join(context_lines)

        customer_label = (tenant_id or "the company").strip()
        messages = [
            {"role": "system", "content": f"You are a FAQ assistant for {customer_label}. Answer the user's question using ONLY the provided FAQ context below. Answer concisely and factually."},
            {"role": "user", "content": f"Question: {question}\n\nContext:\n{context}"},
        ]

        llm_result = self.provider.generate_chat(messages)
        answer = llm_result.data.get("content", "No answer generated.") if llm_result.success else "LLM generation failed."
        grounded = bool(answer) and "do not know" not in answer.lower()
        generation_latency_ms = int((time.perf_counter() - generation_start) * 1000)

        response_chunks = [
            RetrievedChunk(
                rank=i + 1,
                source=c["source"],
                chunkNumber=c.get("chunk_number"),
                score=round(c["rerank_score"], 4),
                excerpt=c["content"],
            )
            for i, c in enumerate(ranked)
        ]

        response = RetrievalQueryResponse(
            tenantId=tenant_id,
            question=question,
            transformedQuery=transformed_query,
            strategy="mcp+query-transform+hybrid-retrieval+rerank+grounded-generation",
            answer=answer,
            chunksUsed=len(response_chunks),
            grounded=grounded,
            retrievalLatencyMs=retrieval_latency_ms,
            generationLatencyMs=generation_latency_ms,
            chunks=response_chunks,
        )

        # Cache and log via MCP
        self.provider.cache_set_response("retrieval", tenant_id, question, response.model_dump_json())
        self.provider.log_analytics(
            query=question, response=answer, customer=tenant_id,
            rag_pattern="retrieval", framework="langchain",
            strategy=response.strategy,
            latency_ms=retrieval_latency_ms + generation_latency_ms,
            context_docs=context,
        )

        return response


class McpCorrectivePipeline:
    """Corrective RAG pipeline using MCP servers."""

    def __init__(self):
        self._provider: McpToolProvider | None = None

    @property
    def provider(self) -> McpToolProvider:
        if self._provider is None:
            self._provider = get_mcp_provider()
        return self._provider

    def health(self) -> dict:
        result = self.provider.chromadb.call_tool("health_check")
        return result.data if result.success else {"status": "DEGRADED"}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        # Check cache
        cached = self.provider.cache_get_response("langchain-corrective", tenant, question)
        if cached.success and cached.data.get("found"):
            return RagResponse(**cached.data["value"])

        # Search via MCP ChromaDB
        search_result = self.provider.similarity_search(question, tenant, top_k=6)
        if not search_result.success:
            return RagResponse(answer="Search failed.", chunksUsed=0, strategy="mcp+crag+error", orchestrationStrategy="mcp-crag")

        documents = search_result.data.get("results", {}).get("documents", [[]])[0]
        if not documents:
            return RagResponse(
                answer="I could not find grounded FAQ evidence for that question.",
                chunksUsed=0, strategy="mcp+crag+no-retrieval", orchestrationStrategy="mcp-crag",
            )

        # Grade documents via MCP LLM Gateway
        grade_result = self.provider.grade_documents(question, documents)
        relevant_docs = []
        irrelevant_docs = []
        if grade_result.success:
            grades = grade_result.data.get("grades", [])
            for doc, grade in zip(documents, grades):
                if grade.get("relevant", True):
                    relevant_docs.append(doc)
                else:
                    irrelevant_docs.append(doc)
        else:
            relevant_docs = documents  # On grading failure, keep all

        relevance_ratio = len(relevant_docs) / len(documents) if documents else 0

        # Web search fallback via MCP
        web_context = ""
        if relevance_ratio < 0.5:
            web_result = self.provider.tavily_search(question)
            if web_result.success and web_result.data.get("results"):
                parts = []
                if web_result.data.get("answer"):
                    parts.append(f"Web Summary: {web_result.data['answer']}")
                for r in web_result.data.get("results", [])[:3]:
                    parts.append(f"Source: {r.get('title', '')}\n{r.get('content', '')}")
                web_context = "\n\n".join(parts)
            strategy = "mcp+crag+web-search-fallback" if web_context else "mcp+crag+low-relevance"
        elif relevance_ratio < 1.0:
            web_result = self.provider.tavily_search(question)
            if web_result.success:
                web_context = web_result.data.get("answer", "")
            strategy = "mcp+crag+ambiguous-supplemented" if web_context else "mcp+crag+ambiguous-local"
        else:
            strategy = "mcp+crag+all-relevant"

        # Build context
        context_parts = relevant_docs if relevant_docs else documents
        if web_context:
            context_parts = list(context_parts) + [f"--- Web Search Results ---\n{web_context}"]
        context = "\n\n".join(context_parts)

        # Generate answer via MCP LLM Gateway
        messages = [
            {"role": "system", "content": "You are a FAQ assistant. Answer the user's question using ONLY the provided context below. Answer concisely and factually."},
            {"role": "user", "content": f"Question: {question}\n\nContext:\n{context}"},
        ]
        llm_result = self.provider.generate_chat(messages)
        answer = llm_result.data.get("content", "No answer generated.") if llm_result.success else "LLM generation failed."

        response = RagResponse(
            answer=answer,
            chunksUsed=len(relevant_docs) if relevant_docs else len(documents),
            strategy=strategy,
            orchestrationStrategy="mcp-crag",
        )

        # Cache and analytics via MCP
        self.provider.cache_set_response("langchain-corrective", tenant, question, response.model_dump_json())
        self.provider.log_analytics(
            query=question, response=answer, customer=customer_id or "default",
            rag_pattern="corrective", framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000), context_docs=context,
        )
        return response


class McpGraphPipeline:
    """Graph RAG pipeline using MCP servers."""

    def __init__(self):
        self._provider: McpToolProvider | None = None

    @property
    def provider(self) -> McpToolProvider:
        if self._provider is None:
            self._provider = get_mcp_provider()
        return self._provider

    def health(self) -> dict:
        result = self.provider.neo4j.call_tool("health_check")
        return result.data if result.success else {"status": "DEGRADED"}

    def rebuild_index(self) -> int:
        result = self.provider.neo4j.call_tool("rebuild_graph_index")
        return 1 if result.success else 0

    def ask(self, question: str, customer_id: str | None = None) -> GraphRagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        # Check cache
        cached = self.provider.cache_get_response("langchain-graph", tenant, question)
        if cached.success and cached.data.get("found"):
            return GraphRagResponse(**cached.data["value"])

        # Search graph via MCP Neo4j
        graph_result = self.provider.graph_search(question)
        graph_facts = []
        if graph_result.success:
            for record in graph_result.data.get("results", []):
                props = record.get("properties", {})
                name = props.get("name", props.get("title", ""))
                content = props.get("content", "")
                if name or content:
                    graph_facts.append(f"{name}: {content}" if content else name)

        # Also search ChromaDB for vector context
        search_result = self.provider.similarity_search(question, tenant, top_k=4)
        vector_docs = []
        if search_result.success:
            vector_docs = search_result.data.get("results", {}).get("documents", [[]])[0]

        # Combine graph + vector context
        context_parts = graph_facts + vector_docs
        if not context_parts:
            return GraphRagResponse(
                answer="I could not find grounded FAQ evidence for that question.",
                graphFacts=0, strategy="mcp+graph+no-results", orchestrationStrategy="mcp-graph",
            )

        context = "\n\n".join(context_parts)

        # Generate via MCP LLM Gateway
        messages = [
            {"role": "system", "content": "You are a FAQ assistant with access to a knowledge graph. Use the graph context and FAQ documents to answer. Answer concisely and factually."},
            {"role": "user", "content": f"Question: {question}\n\nKnowledge Graph Context:\n{context}"},
        ]
        llm_result = self.provider.generate_chat(messages)
        answer = llm_result.data.get("content", "No answer generated.") if llm_result.success else "LLM generation failed."

        response = GraphRagResponse(
            answer=answer,
            graphFacts=len(graph_facts),
            strategy="mcp+graph-rag+vector-augmented",
            orchestrationStrategy="mcp-graph",
        )

        self.provider.cache_set_response("langchain-graph", tenant, question, response.model_dump_json())
        self.provider.log_analytics(
            query=question, response=answer, customer=customer_id or "default",
            rag_pattern="graph", framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000), context_docs=context,
        )
        return response


class McpAgenticPipeline:
    """Agentic RAG pipeline using MCP servers.

    The agent dynamically discovers available MCP tools and decides which to invoke.
    """

    def __init__(self):
        self._provider: McpToolProvider | None = None

    @property
    def provider(self) -> McpToolProvider:
        if self._provider is None:
            self._provider = get_mcp_provider()
        return self._provider

    def health(self) -> dict:
        results = {}
        for name, client in [("chromadb", self.provider.chromadb), ("neo4j", self.provider.neo4j)]:
            r = client.call_tool("health_check")
            results[name] = r.data.get("status", "UNKNOWN") if r.success else "DOWN"
        overall = "UP" if all(v == "UP" for v in results.values()) else "DEGRADED"
        return {"status": overall, "backends": results}

    def rebuild_index(self) -> int:
        return 0

    def ask(self, question: str, customer_id: str | None = None) -> RagResponse:
        _t0 = time.perf_counter()
        tenant = (customer_id or "default").strip()

        # Agentic: let LLM decide which tools to call
        # First, search both vector and graph
        search_result = self.provider.similarity_search(question, tenant, top_k=6)
        graph_result = self.provider.graph_search(question)

        context_parts = []
        if search_result.success:
            docs = search_result.data.get("results", {}).get("documents", [[]])[0]
            context_parts.extend(docs)
        if graph_result.success:
            for record in graph_result.data.get("results", []):
                props = record.get("properties", {})
                name = props.get("name", props.get("title", ""))
                content = props.get("content", "")
                if name or content:
                    context_parts.append(f"[Graph] {name}: {content}" if content else f"[Graph] {name}")

        if not context_parts:
            # Fallback to web search
            web_result = self.provider.tavily_search(question)
            if web_result.success and web_result.data.get("answer"):
                context_parts.append(f"[Web] {web_result.data['answer']}")
                for r in web_result.data.get("results", [])[:2]:
                    context_parts.append(f"[Web] {r.get('title', '')}: {r.get('content', '')}")

        if not context_parts:
            return RagResponse(
                answer="I could not find any relevant information.",
                chunksUsed=0, strategy="mcp+agentic+no-context", orchestrationStrategy="mcp-agentic",
            )

        context = "\n\n".join(context_parts)
        messages = [
            {"role": "system", "content": "You are an intelligent FAQ assistant with access to vector search, knowledge graph, and web search. Synthesize information from all available sources to provide the best answer."},
            {"role": "user", "content": f"Question: {question}\n\nContext:\n{context}"},
        ]
        llm_result = self.provider.generate_chat(messages)
        answer = llm_result.data.get("content", "No answer generated.") if llm_result.success else "LLM generation failed."

        response = RagResponse(
            answer=answer,
            chunksUsed=len(context_parts),
            strategy="mcp+agentic+multi-source",
            orchestrationStrategy="mcp-agentic",
        )
        self.provider.log_analytics(
            query=question, response=answer, customer=customer_id or "default",
            rag_pattern="agentic", framework="langchain", strategy=response.strategy,
            latency_ms=int((time.perf_counter() - _t0) * 1000), context_docs=context,
        )
        return response
