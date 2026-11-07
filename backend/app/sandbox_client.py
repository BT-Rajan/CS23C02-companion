"""How the API reaches the sandbox (ADR 0006 §4). The client never talks to it; only this module does.

http  – the separate sandbox service; requests are signed with SANDBOX_KEY and its signed answers are verified, including
        that the answer is for the job we sent (a forged or swapped result is refused).
local – imports the sandbox package and runs it in this process (tests and development only; refused in prod by config).
"""
import json
import pathlib
import sys
import uuid

import httpx

from .config import get_settings
from .errors import ApiError

UNAVAILABLE = ApiError(503, "Checking isn't available right now. Your code is safe in the app; try again in a moment.", "sandbox_unavailable")


def _sandbox_pkg():
    root = pathlib.Path(__file__).resolve().parents[2] / "sandbox"
    if str(root) not in sys.path:
        sys.path.insert(0, str(root))
    from sandbox import grader, runner, signing  # noqa: PLC0415
    return grader, runner, signing


def grade(code: str, tests: list[dict]) -> dict:
    s = get_settings()
    if s.sandbox_mode == "off":
        raise UNAVAILABLE
    grader, runner, signing = _sandbox_pkg()
    if s.sandbox_mode == "local":
        if not runner.available():
            raise UNAVAILABLE
        return grader.grade(code, tests)
    key = bytes.fromhex(s.sandbox_key)
    job_id = str(uuid.uuid4())
    body = json.dumps({"job_id": job_id, "code": code, "tests": tests}, separators=(",", ":")).encode()
    try:
        r = httpx.post(s.sandbox_url.rstrip("/") + "/v1/grade", content=body, timeout=120,
                       headers={"Content-Type": "application/json", **signing.headers(key, body)})
    except httpx.HTTPError:
        raise UNAVAILABLE
    if not signing.verify(key, r.headers.get("X-Sandbox-Timestamp"), r.headers.get("X-Sandbox-Signature"), r.content):
        raise UNAVAILABLE  # unsigned or forged answer: never trusted
    if r.status_code != 200:
        raise UNAVAILABLE
    out = json.loads(r.content)
    if out.get("job_id") != job_id:
        raise UNAVAILABLE
    return out
