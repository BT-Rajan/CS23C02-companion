"""The sandbox as a small HTTP service on a private network (ADR 0006 §4). It has no database and no user data: it receives
code and tests, runs them, and returns a signed result. Run:  SANDBOX_KEY=<hex> uvicorn sandbox.service:app --port 8090

Every request must be signed by the API with the shared key, fresh (60 s) and carry a job id not seen before (replay).
Every response is signed, so the API can tell a real result from a forged one.
"""
import collections
import json
import os
import threading

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response

from . import grader, runner, signing

MAX_BODY = 256 * 1024
app = FastAPI(title="CS23C02 sandbox", docs_url=None, redoc_url=None, openapi_url=None)
_seen: "collections.OrderedDict[str, None]" = collections.OrderedDict()
_seen_lock = threading.Lock()


def _key() -> bytes:
    k = os.environ.get("SANDBOX_KEY", "")
    if len(k) < 64:
        raise RuntimeError("SANDBOX_KEY must be at least 32 bytes as hex")
    return bytes.fromhex(k)


def _signed(status: int, payload: dict) -> Response:
    body = json.dumps(payload, separators=(",", ":")).encode()
    return Response(body, status_code=status, media_type="application/json", headers=signing.headers(_key(), body))


@app.get("/healthz")
def healthz():
    return {"ok": runner.available(), "isolation": runner.isolation()}


@app.post("/v1/grade")
async def grade(request: Request):
    body = await request.body()
    if len(body) > MAX_BODY:
        return JSONResponse({"error": "too large"}, 413)
    if not signing.verify(_key(), request.headers.get("X-Sandbox-Timestamp"), request.headers.get("X-Sandbox-Signature"), body):
        return JSONResponse({"error": "unauthenticated"}, 401)
    try:
        job = json.loads(body)
        job_id, code, tests = str(job["job_id"]), str(job["code"]), list(job["tests"])
    except (ValueError, KeyError, TypeError):
        return _signed(422, {"error": "bad job"})
    with _seen_lock:
        if job_id in _seen:
            return _signed(409, {"error": "replayed job"})
        _seen[job_id] = None
        while len(_seen) > 10000:
            _seen.popitem(last=False)
    if len(code) > 20000:
        return _signed(422, {"error": "code too long"})
    try:
        result = grader.grade(code, tests)
    except ValueError as e:
        return _signed(422, {"error": str(e)})
    return _signed(200, {"job_id": job_id, **result})
