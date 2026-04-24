FROM python:3.11-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /build
COPY requirements.txt .
RUN pip install --upgrade pip && pip wheel --wheel-dir /wheels -r requirements.txt

FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    APP_PORT=9191

RUN useradd --create-home appuser
WORKDIR /app

COPY --from=builder /wheels /wheels
RUN pip install --no-cache-dir /wheels/* && rm -rf /wheels

COPY app ./app
USER appuser

EXPOSE 9191

# ENTRYPOINT_MODE: "api" (default) or "worker" (Celery)
ENV ENTRYPOINT_MODE=api
CMD ["sh", "-c", "if [ \"$ENTRYPOINT_MODE\" = 'worker' ]; then celery -A app.worker.celery_app worker --loglevel=info --concurrency=2; else uvicorn app.main:app --host 0.0.0.0 --port ${APP_PORT}; fi"]
