"""Shared HTTP client pool for all pipelines.

Provides a connection-pooled httpx.Client for ChromaDB and other HTTP services,
and a connection-pooled ChatOpenAI instance for LLM calls.
"""
import chromadb
import httpx
from langchain_openai import ChatOpenAI

from .cached_embeddings import CachedOpenAIEmbeddings
from .config import settings

# --- httpx connection pool for general HTTP (e.g., Tavily, analytics) ---
_http_pool = httpx.Client(
    limits=httpx.Limits(max_connections=50, max_keepalive_connections=20),
    timeout=httpx.Timeout(connect=5.0, read=30.0, write=10.0, pool=10.0),
)

# --- Shared ChromaDB client (reuses internal requests.Session) ---
_chroma_client = chromadb.HttpClient(
    host=settings.chroma_host,
    port=settings.chroma_port,
)

# --- Shared embedding model (Redis-cached) ---
_embeddings = CachedOpenAIEmbeddings(model=settings.openai_embedding_model)

# --- Shared LLM client (OpenAI uses httpx internally, benefits from reuse) ---
_llm = ChatOpenAI(model=settings.openai_chat_model, temperature=0)


def get_http_client() -> httpx.Client:
    return _http_pool


def get_chroma_client() -> chromadb.HttpClient:
    return _chroma_client


def get_embeddings() -> CachedOpenAIEmbeddings:
    return _embeddings


def get_llm() -> ChatOpenAI:
    return _llm
