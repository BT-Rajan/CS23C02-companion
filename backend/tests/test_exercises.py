"""A6 exercises: graded in the sandbox with public + hidden tests; hints/solution by policy (open question #3)."""
import json
import pathlib
import sys

import pytest
from sqlalchemy import select

from app import sandbox_client
from app.config import get_settings
from app.db import SessionLocal
from app.models import AuditLog, ExerciseAttempt
from tests.conftest import import_fixture, owner, signed_in

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[2] / "sandbox"))
from sandbox import runner  # noqa: E402

pytestmark = pytest.mark.skipif(not runner.available(), reason="bubblewrap or python3.12 not installed")
RIGHT = "n = int(input())\nprint(n * 2)\n"
WRONG = "n = int(input())\nprint(n + 2)\n"   # passes the public test (4 -> 8 would be 6: no), fails both hidden


def submit(client, h, code, ex="EXR-0001", **extra):
    r = client.post(f"/v1/exercises/{ex}/submissions", json={"code": code, **extra}, headers=h)
    if r.status_code == 200 and any(t["status"] == "sandbox_error" for t in r.json()["public"]):
        pytest.fail("sandbox could not start: " + str(r.json()["public"][0]["error"]))  # say why, not just 'False is True'
    return r


def help_(client, h, ex="EXR-0001"):
    return client.get(f"/v1/exercises/{ex}/help", headers=h).json()


def test_a_right_solution_passes_every_test_and_unlocks_the_solution(client):
    import_fixture()
    _, h = owner(client)
    r = submit(client, h, RIGHT)
    assert r.status_code == 200, r.text
    j = r.json()
    assert j["passed"] is True and j["hidden"] == {"passed": 2, "total": 2, "first_failure": None}
    assert j["public"] == [{"stdin": "4\n", "expect": "8\n", "got": "8\n", "passed": True, "status": "ok", "error": None}]
    assert j["help"]["solution_unlocked"] is True
    hp = help_(client, h)
    assert hp["solution"]["code"].startswith("n = int(input())") and len(hp["hints"]) == 2


def test_hidden_tests_never_reveal_their_data(client):
    import_fixture()
    _, h = owner(client)
    j = submit(client, h, "print(int(input()) * 3)").json()   # wrong for 4, -3, 0? 0*3 == 0 passes one hidden
    assert j["passed"] is False and j["hidden"]["total"] == 2 and j["hidden"]["first_failure"] == "wrong output"
    text = json.dumps(j)
    assert "-3" not in text and "-6" not in text and "-9" not in text   # hidden inputs/expected/actual stay on the server


def test_errors_and_runaways_are_reported_plainly(client):
    import_fixture()
    _, h = owner(client)
    j = submit(client, h, "print(1/0)").json()
    assert j["public"][0]["status"] == "error" and j["public"][0]["error"] == "ZeroDivisionError: division by zero"
    j = submit(client, h, "while True:\n    pass").json()
    assert j["public"][0]["status"] == "timeout" and j["hidden"]["first_failure"] == "ran too long"


def test_hints_and_solution_follow_the_approved_policy(client):
    import_fixture()
    _, h = owner(client)
    seen = []
    for i in range(5):
        st = submit(client, h, WRONG).json()["help"]
        seen.append((st["failed_checks"], st["hints_unlocked"], st["solution_unlocked"]))
        hp = help_(client, h)
        assert len(hp["hints"]) == st["hints_unlocked"] and (hp["solution"] is not None) == st["solution_unlocked"]
    assert seen == [(1, 1, False), (2, 1, False), (3, 2, False), (4, 2, False), (5, 2, True)]


def test_help_before_any_check_releases_nothing_and_is_audited(client):
    import_fixture()
    u, h = owner(client)
    hp = help_(client, h)
    assert hp["hints"] == [] and hp["solution"] is None and hp["next_hint_in"] == 1 and hp["solution_in"] == 5
    with SessionLocal() as db:
        assert db.scalar(select(AuditLog.outcome).where(AuditLog.action == "exercise_help", AuditLog.actor_id == u.id)) == "hints:0"


def test_checks_are_recorded_and_a_pass_shows_in_progress(client):
    import_fixture()
    u, h = owner(client)
    submit(client, h, WRONG); submit(client, h, RIGHT)
    with SessionLocal() as db:
        rows = db.scalars(select(ExerciseAttempt).where(ExerciseAttempt.user_id == u.id)).all()
    assert [r.passed for r in rows] == [False, True] and all(len(r.code_sha256) == 64 for r in rows)
    assert client.get("/v1/progress", headers=h).json()["exercises_passed"] == ["EXR-0001"]


def test_access_rules(client):
    import_fixture()
    assert client.post("/v1/exercises/EXR-0001/submissions", json={"code": RIGHT}).status_code == 401
    _, _, h = signed_in(client)
    assert submit(client, h, RIGHT).status_code == 403                          # no book activated
    _, h = owner(client)
    assert submit(client, h, RIGHT, ex="EXR-9999").status_code == 404
    assert submit(client, h, RIGHT, passed=True).status_code == 422             # client cannot claim a verdict
    assert submit(client, h, "x" * 20001).status_code == 422
    assert client.post("/v1/exercises/ITM-0001/submissions", json={"code": RIGHT}, headers=h).status_code == 422


def test_per_exercise_rate_limit(client):
    import_fixture()
    _, h = owner(client)
    codes = [submit(client, h, "print(8)").status_code for _ in range(21)]
    assert codes[:20] == [200] * 20 and codes[20] == 429


def test_one_check_at_a_time_per_student(client):
    import_fixture()
    u, h = owner(client)
    from sqlalchemy import text
    with SessionLocal() as db:   # another request of this student is still running (it holds the lock)
        db.execute(text("SELECT GET_LOCK(:n, 0)"), {"n": f"exr-run:{u.id}"})
        r = submit(client, h, RIGHT)
        db.execute(text("SELECT RELEASE_LOCK(:n)"), {"n": f"exr-run:{u.id}"})
    assert r.status_code == 429 and r.json()["error"]["code"] == "check_in_progress"
    assert submit(client, h, RIGHT).status_code == 200


def test_sandbox_off_or_unreachable_is_a_clear_503(client, monkeypatch):
    import_fixture()
    _, h = owner(client)
    st = get_settings()
    monkeypatch.setattr(st, "sandbox_mode", "off")
    r = submit(client, h, RIGHT)
    assert r.status_code == 503 and r.json()["error"]["code"] == "sandbox_unavailable"
    monkeypatch.setattr(st, "sandbox_mode", "http")
    monkeypatch.setattr(st, "sandbox_url", "http://127.0.0.1:9")
    monkeypatch.setattr(st, "sandbox_key", "cd" * 32)
    assert submit(client, h, RIGHT).status_code == 503
    with SessionLocal() as db:
        assert db.scalar(select(ExerciseAttempt)) is None   # a check that never ran is not counted as a failure


def test_http_mode_refuses_a_forged_sandbox_answer(client, monkeypatch):
    """The API must not trust a result it cannot verify (ADR 0006: results are signed by the sandbox)."""
    import httpx
    import_fixture()
    _, h = owner(client)
    st = get_settings()
    monkeypatch.setattr(st, "sandbox_mode", "http")
    monkeypatch.setattr(st, "sandbox_url", "http://sandbox.invalid")
    monkeypatch.setattr(st, "sandbox_key", "cd" * 32)
    forged = {"job_id": "x", "passed": 2, "total": 2, "tests": []}
    monkeypatch.setattr(httpx, "post", lambda *a, **k: httpx.Response(200, json=forged, headers={"X-Sandbox-Signature": "0" * 64,
                                                                                                "X-Sandbox-Timestamp": "1"}))
    assert submit(client, h, "print('anything')").status_code == 503
