"""Pytest configuration and fixtures."""
import pytest
from unittest.mock import MagicMock
import sys
import os

# Add app to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

# Pre-mock heavy external clients at module level so the pipeline singleton
# can be created without live infrastructure (Redis, ChromaDB, OpenAI).
import chromadb
import redis
chromadb.HttpClient = MagicMock()
redis.Redis = MagicMock()

import langchain_openai
langchain_openai.ChatOpenAI = MagicMock()

# Mock CachedOpenAIEmbeddings before pipeline imports it
import app.cached_embeddings
app.cached_embeddings.CachedOpenAIEmbeddings = MagicMock()

# Mock analytics fire-and-forget
import app.analytics_client
app.analytics_client.post_analytics_event = MagicMock()

from app.main import app
from app.security import generate_token


@pytest.fixture
def client():
    """FastAPI test client."""
    from fastapi.testclient import TestClient
    return TestClient(app)


@pytest.fixture
def auth_token():
    """Generate valid auth token."""
    return generate_token("testuser", "tenant-1", "USER")


@pytest.fixture
def admin_token():
    """Generate admin auth token."""
    return generate_token("admin", "smoke_tenant", "ADMIN")


@pytest.fixture
def invalid_token():
    """Provide invalid token."""
    return "invalid-token-string"
