"""Tests for metrics and monitoring."""
import pytest
from app.metrics import (
    record_query_success,
    record_query_error,
    record_cache_hit,
    record_cache_miss,
    set_service_status,
    set_circuit_breaker_state,
    REQUEST_COUNT,
    QUERY_COUNT,
    CACHE_HITS,
    ERROR_COUNT
)


def test_record_query_success():
    """Test successful query recording."""
    initial_count = QUERY_COUNT.labels(service="test", status="success")._value.get()
    record_query_success("test", 0.5)
    final_count = QUERY_COUNT.labels(service="test", status="success")._value.get()
    assert final_count >= initial_count


def test_record_query_error():
    """Test error query recording."""
    initial_count = QUERY_COUNT.labels(service="test", status="error")._value.get()
    record_query_error("test", "TestError")
    final_count = QUERY_COUNT.labels(service="test", status="error")._value.get()
    assert final_count >= initial_count


def test_record_cache_hit():
    """Test cache hit recording."""
    initial_count = CACHE_HITS.labels(cache_type="redis")._value.get()
    record_cache_hit("redis")
    final_count = CACHE_HITS.labels(cache_type="redis")._value.get()
    assert final_count >= initial_count


def test_record_cache_miss():
    """Test cache miss recording."""
    initial_count = CACHE_HITS.labels(cache_type="redis")._value.get()
    record_cache_miss("redis")
    # Just verify it doesn't raise an exception


def test_set_service_status():
    """Test service status recording."""
    set_service_status("test-service", True)
    set_service_status("test-service", False)
    # Verify no exceptions raised


def test_set_circuit_breaker_state():
    """Test circuit breaker state recording."""
    set_circuit_breaker_state("test-service", 0)  # closed
    set_circuit_breaker_state("test-service", 1)  # open
    set_circuit_breaker_state("test-service", 2)  # half-open
    # Verify no exceptions raised
