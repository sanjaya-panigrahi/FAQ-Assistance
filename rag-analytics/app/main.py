from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import router
from .db import Base, engine


app = FastAPI(title="RAG Analytics Service", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup() -> None:
    Base.metadata.create_all(bind=engine)


app.include_router(router)
