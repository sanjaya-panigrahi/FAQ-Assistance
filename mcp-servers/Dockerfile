FROM python:3.11-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build
COPY requirements.txt .
RUN pip install --upgrade pip && pip wheel --wheel-dir /wheels -r requirements.txt

FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

RUN useradd --create-home appuser
WORKDIR /app

COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir /wheels/* && rm -rf /wheels

COPY shared ./shared
COPY chromadb_server.py .
COPY neo4j_server.py .
COPY llm_gateway_server.py .
COPY web_search_server.py .
COPY cache_server.py .
COPY analytics_server.py .
COPY entrypoint.py .
COPY __init__.py .

USER appuser

# Default: run ChromaDB server. Override with docker-compose command.
CMD ["python", "entrypoint.py", "chromadb_server", "8301"]
