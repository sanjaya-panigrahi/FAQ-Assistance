"""Shared authentication middleware for MCP servers."""

import os
import functools
import logging

logger = logging.getLogger(__name__)

_MCP_API_KEY = os.getenv("MCP_API_KEY", "")


def validate_api_key(api_key: str | None) -> bool:
    """Validate an API key against the configured MCP_API_KEY.

    If MCP_API_KEY is not set, auth is disabled (development mode).
    """
    if not _MCP_API_KEY:
        return True  # Auth disabled in dev
    if not api_key:
        return False
    # Constant-time comparison to prevent timing attacks
    import hmac
    return hmac.compare_digest(api_key, _MCP_API_KEY)
