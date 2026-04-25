from .celery_app import celery_app
from .pipelines.graph import GraphPipeline


@celery_app.task(bind=True, max_retries=3, name="langchain_neo4j_graph.rebuild_index")
def rebuild_index_task(self):
    pipeline = GraphPipeline()
    try:
        count = pipeline.rebuild_index()
        return {"status": "COMPLETE", "documents": count}
    except Exception as exc:
        raise self.retry(exc=exc, countdown=2 ** self.request.retries)
    finally:
        pipeline.close()
