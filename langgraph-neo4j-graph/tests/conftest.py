"""Pytest configuration and fixtures."""
import pytest
from fastapi.testclient import TestClient
import sys
import os

# Add app to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from app.main import app
from app.security import generate_token


@pytest.fixture
def client():
    """FastAPI test client."""
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
