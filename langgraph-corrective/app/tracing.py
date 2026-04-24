"""OpenTelemetry tracing setup with Zipkin exporter for Python RAG services."""

from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.sdk.resources import Resource
from opentelemetry.exporter.zipkin.json import ZipkinExporter
from opentelemetry.instrumentation.fastapi import FastAPIInstrumentor


def setup_tracing(app, *, service_name: str, zipkin_host: str = "zipkin",
                  zipkin_port: int = 9411, sample_rate: float = 1.0,
                  enabled: bool = True):
    """Initialise OpenTelemetry tracing and instrument the FastAPI app."""
    if not enabled:
        return

    resource = Resource.create({"service.name": service_name})
    provider = TracerProvider(resource=resource)

    zipkin_exporter = ZipkinExporter(
        endpoint=f"http://{zipkin_host}:{zipkin_port}/api/v2/spans"
    )
    provider.add_span_processor(BatchSpanProcessor(zipkin_exporter))
    trace.set_tracer_provider(provider)

    FastAPIInstrumentor.instrument_app(app)


def shutdown_tracing():
    """Flush pending spans and shut down the tracer provider."""
    provider = trace.get_tracer_provider()
    if hasattr(provider, "shutdown"):
        provider.shutdown()
