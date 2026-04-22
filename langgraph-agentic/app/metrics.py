"""Prometheus metrics and monitoring for FastAPI."""
from fastapi import Request
from prometheus_client import Counter, Histogram, Gauge, REGISTRY, generate_latest
from starlette.middleware.base import BaseHTTPMiddleware
from time import time

# Metrics
REQUEST_COUNT = Counter(
    'faq_requests_total',
    'Total HTTP requests',
    ['method', 'endpoint', 'status']
)

REQUEST_LATENCY = Histogram(
    'faq_request_duration_seconds',
    'HTTP request latency',
    ['method', 'endpoint'],
    buckets=(0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0)
)

QUERY_COUNT = Counter(
    'faq_queries_total',
    'Total FAQ queries processed',
    ['service', 'status']
)

QUERY_LATENCY = Histogram(
    'faq_query_duration_seconds',
    'FAQ query processing latency',
    ['service'],
    buckets=(0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0)
)

CACHE_HITS = Counter(
    'faq_cache_hits_total',
    'Total cache hits',
    ['cache_type']
)

CACHE_MISSES = Counter(
    'faq_cache_misses_total',
    'Total cache misses',
    ['cache_type']
)

ERROR_COUNT = Counter(
    'faq_errors_total',
    'Total errors',
    ['error_type', 'service']
)

SERVICE_UP = Gauge(
    'faq_service_up',
    'Service availability (1=up, 0=down)',
    ['service']
)

CIRCUIT_BREAKER_STATE = Gauge(
    'faq_circuit_breaker_state',
    'Circuit breaker state (0=closed, 1=open, 2=half-open)',
    ['service']
)


class MetricsMiddleware(BaseHTTPMiddleware):
    """Middleware to track HTTP request metrics."""

    async def dispatch(self, request: Request, call_next):
        start_time = time()
        method = request.method
        endpoint = request.url.path

        response = None
        try:
            response = await call_next(request)
            status_code = response.status_code
        except Exception as exc:
            status_code = 500
            ERROR_COUNT.labels(error_type=type(exc).__name__, service="http").inc()
            raise
        finally:
            latency = time() - start_time
            REQUEST_COUNT.labels(method=method, endpoint=endpoint, status=status_code).inc()
            REQUEST_LATENCY.labels(method=method, endpoint=endpoint).observe(latency)

        return response


def get_metrics():
    """Get Prometheus metrics in text format."""
    return generate_latest(REGISTRY)


def record_query_success(service: str, latency: float):
    """Record successful query."""
    QUERY_COUNT.labels(service=service, status="success").inc()
    QUERY_LATENCY.labels(service=service).observe(latency)


def record_query_error(service: str, error_type: str):
    """Record failed query."""
    QUERY_COUNT.labels(service=service, status="error").inc()
    ERROR_COUNT.labels(error_type=error_type, service=service).inc()


def record_cache_hit(cache_type: str):
    """Record cache hit."""
    CACHE_HITS.labels(cache_type=cache_type).inc()


def record_cache_miss(cache_type: str):
    """Record cache miss."""
    CACHE_MISSES.labels(cache_type=cache_type).inc()


def set_service_status(service: str, is_up: bool):
    """Set service status (up/down)."""
    SERVICE_UP.labels(service=service).set(1 if is_up else 0)


def set_circuit_breaker_state(service: str, state: int):
    """Set circuit breaker state (0=closed, 1=open, 2=half-open)."""
    CIRCUIT_BREAKER_STATE.labels(service=service).set(state)
