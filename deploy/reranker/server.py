"""Local cross-encoder: no embeddings, no LLM tools, no stored document text."""
import os
import threading
from contextlib import asynccontextmanager

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import CrossEncoder
from scoring import score_documents

MODEL = os.getenv("RERANKER_MODEL", "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1")
gate = threading.BoundedSemaphore(1)
model = None


@asynccontextmanager
async def lifespan(app):
    global model
    torch.set_num_threads(int(os.getenv("RERANKER_THREADS", "4")))
    model = CrossEncoder(MODEL, device="cpu", max_length=512, activation_fn=torch.nn.Sigmoid())
    yield


app = FastAPI(lifespan=lifespan)


class Request(BaseModel):
    query: str = Field(min_length=1, max_length=4000)
    documents: list[str] = Field(min_length=1, max_length=50)


@app.get("/health")
def health():
    return {"ready": model is not None, "model": MODEL}


@app.post("/rerank")
def rerank(request: Request):
    if any(not d.strip() or len(d) > 30000 for d in request.documents) or sum(map(len, request.documents)) > 500000:
        raise HTTPException(422, "Document size limit exceeded")
    if not gate.acquire(blocking=False):
        raise HTTPException(429, "Reranker busy")
    try:
        # Preserve passage tails: use overlapping windows instead of silent 512-token truncation.
        return {"scores": score_documents(model, request.query, request.documents), "model": MODEL}
    except Exception:
        raise HTTPException(503, "Reranker inference failed") from None
    finally:
        gate.release()
