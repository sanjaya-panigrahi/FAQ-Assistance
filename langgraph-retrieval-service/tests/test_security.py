"""Tests for security module."""
import pytest
from datetime import timedelta
from app.security import (
    generate_token,
    generate_refresh_token,
    verify_token,
    TokenPayload
)
import jwt


def test_generate_token():
    """Test JWT token generation."""
    token = generate_token("testuser", "tenant-1", "USER")
    assert token is not None
    assert isinstance(token, str)


def test_generate_token_with_custom_expiration():
    """Test token generation with custom expiration."""
    expires_delta = timedelta(hours=1)
    token = generate_token("testuser", "tenant-1", "ADMIN", expires_delta)
    assert token is not None


def test_verify_token_success():
    """Test successful token verification."""
    token = generate_token("testuser", "tenant-1", "USER")
    payload = verify_token(token)
    
    assert payload.sub == "testuser"
    assert payload.tenant_id == "tenant-1"
    assert payload.role == "USER"


def test_verify_token_invalid():
    """Test verification of invalid token."""
    with pytest.raises(Exception):  # HTTPException
        verify_token("invalid-token")


def test_verify_token_expired():
    """Test verification of expired token."""
    # Create token with negative expiration (already expired)
    expires_delta = timedelta(hours=-1)
    token = generate_token("testuser", "tenant-1", "USER", expires_delta)
    
    with pytest.raises(Exception):  # HTTPException for expired token
        verify_token(token)


def test_generate_refresh_token():
    """Test refresh token generation."""
    token = generate_refresh_token("testuser", "tenant-1")
    assert token is not None
    
    # Verify refresh token is valid
    payload = verify_token(token)
    assert payload.sub == "testuser"
    assert payload.tenant_id == "tenant-1"


def test_token_contains_required_claims():
    """Test that token contains all required claims."""
    token = generate_token("testuser", "tenant-1", "ADMIN")
    
    # Decode without verification to check claims
    from app.security import JWT_SECRET, JWT_ALGORITHM
    decoded = jwt.decode(token, JWT_SECRET, algorithms=[JWT_ALGORITHM])
    
    assert "sub" in decoded
    assert "tenant_id" in decoded
    assert "role" in decoded
    assert "exp" in decoded
    assert "iat" in decoded


def test_token_role_variations():
    """Test tokens with different roles."""
    for role in ["USER", "ADMIN", "SERVICE"]:
        token = generate_token("testuser", "tenant-1", role)
        payload = verify_token(token)
        assert payload.role == role
