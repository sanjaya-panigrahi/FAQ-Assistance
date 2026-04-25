import os

from celery import Celery


broker_url = os.getenv("CELERY_BROKER_URL", "redis://redis-cache:6379/2")
result_backend = os.getenv("CELERY_RESULT_BACKEND", "redis://redis-cache:6379/3")

celery_app = Celery(
    "langchain_neo4j_graph",
    broker=broker_url,
    backend=result_backend,
    include=["app.tasks"],
)
celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_track_started=True,
)

import app.tasks  # noqa: E402,F401
