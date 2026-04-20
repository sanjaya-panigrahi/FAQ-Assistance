from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from .api import router


app = FastAPI(title="LangGraph Retrieval Service", version="1.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)
app.include_router(router)
