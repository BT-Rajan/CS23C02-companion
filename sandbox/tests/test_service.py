import json
import os
import pathlib
import sys
import time
import uuid

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
os.environ.setdefault("SANDBOX_KEY", "ab" * 32)
from fastapi.testclient import TestClient  # noqa: E402

from sandbox import grader, runner, signing, service  # noqa: E402

KEY = bytes.fromhex(os.environ["SANDBOX_KEY"])
pytestmark = pytest.mark.skipif(not runner.available(), reason="bubblewrap or python3.12 not installed")
client = TestClient(service.app)


def post(job, key=KEY, ts=None, tamper=False):
    body = json.dumps(job).encode()
    h = signing.headers(key, body)
    if ts is not None:
        h["X-Sandbox-Timestamp"] = ts
        h["X-Sandbox-Signature"] = signing.sign(key, ts, body)
    if tamper:
        body = body.replace(b"print", b"PRINT")
    return client.post("/v1/grade", content=body, headers=h)


def job(code="print(int(input()) * 2)", tests=None):
    return {"job_id": str(uuid.uuid4()), "code": code,
            "tests": tests or [{"stdin": "4\n", "expect": "8\n"}, {"stdin": "-3\n", "expect": "-6"}, {"stdin": "1\n", "expect": "3\n"}]}


def test_grades_each_test_and_signs_the_answer():
    r = post(job())
    assert r.status_code == 200
    assert signing.verify(KEY, r.headers["X-Sandbox-Timestamp"], r.headers["X-Sandbox-Signature"], r.content)
    j = r.json()
    assert (j["passed"], j["total"]) == (2, 3) and [t["passed"] for t in j["tests"]] == [True, True, False]


def test_unsigned_wrongly_signed_stale_or_tampered_requests_are_refused():
    body = json.dumps(job()).encode()
    assert client.post("/v1/grade", content=body).status_code == 401
    assert post(job(), key=b"x" * 32).status_code == 401
    assert post(job(), ts=str(int(time.time()) - 600)).status_code == 401
    assert post(job(), tamper=True).status_code == 401


def test_replayed_job_is_refused():
    j = job()
    assert post(j).status_code == 200
    assert post(j).status_code == 409


def test_bad_jobs():
    assert post({"job_id": "x"}).status_code == 422
    assert post(job(tests=[{"stdin": "", "expect": ""}] * 21)).status_code == 422
    assert post(job(code="x" * 20001)).status_code == 422


def test_output_comparison_rules():
    assert grader.same_output("8\n", "8") and grader.same_output("a  \nb\n\n", "a\nb")
    assert not grader.same_output("8 \n9", "8\n 9") and not grader.same_output("A", "a")
