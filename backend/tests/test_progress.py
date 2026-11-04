import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app.db import SessionLocal
from app.main import app
from app.models import ProgressEvent
from tests.conftest import import_fixture, ip_headers, login, make_user, owner, signed_in, grant


def ev(kind, lid, eid=None):
    return {"id": eid or str(uuid.uuid4()), "kind": kind, "content_id": lid}


def put(client, h, events, **extra):
    return client.put("/v1/progress/events", json={"events": events, **extra}, headers={**h, **ip_headers()})


def test_progress_requires_login_activation_and_published_content(client):
    assert client.get("/v1/progress").status_code == 401
    assert client.put("/v1/progress/events", json={"events": [ev("lesson_opened", "LSN-0001")]}).status_code == 401
    _, _, h = signed_in(client)
    assert client.get("/v1/progress", headers=h).status_code == 403
    import_fixture(publish=False)
    _, h2 = owner(client)
    assert client.get("/v1/progress", headers=h2).status_code == 404  # nothing published yet


def test_events_are_idempotent_and_unknown_content_is_reported_per_event(client):
    import_fixture()
    _, h = owner(client)
    a, b = ev("lesson_opened", "LSN-0001"), ev("lesson_completed", "LSN-0001")
    r1 = put(client, h, [a, b, ev("lesson_opened", "LSN-9999")]).json()["results"]
    assert [x["status"] for x in r1] == ["accepted", "accepted", "unknown_content"]
    r2 = put(client, h, [a, b]).json()["results"]  # retry of the same batch after a lost response
    assert [x["status"] for x in r2] == ["duplicate", "duplicate"]
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(ProgressEvent)) == 2


def test_the_client_cannot_name_a_user_a_score_or_another_kind_of_event(client):
    import_fixture()
    u, h = owner(client)
    other = make_user()
    for bad in ({"id": str(uuid.uuid4()), "kind": "lesson_opened", "content_id": "LSN-0001", "user_id": other.id},
                {"id": str(uuid.uuid4()), "kind": "assessment_passed", "content_id": "LSN-0001"},
                {"id": str(uuid.uuid4()), "kind": "lesson_completed", "content_id": "ASM-0001"},
                {"id": "not-a-uuid", "kind": "lesson_opened", "content_id": "LSN-0001"}):
        assert put(client, h, [bad]).status_code == 422, bad
    assert put(client, h, [ev("lesson_opened", "LSN-0001")], user_id=other.id).status_code == 422
    assert put(client, h, []).status_code == 422
    assert put(client, h, [ev("lesson_opened", "LSN-0001") for _ in range(101)]).status_code == 422
    with SessionLocal() as db:
        assert db.scalar(select(func.count()).select_from(ProgressEvent)) == 0


def test_each_account_sees_only_its_own_progress_and_cannot_block_anothers_event_ids(client):
    import_fixture()
    ua, ha = owner(client)
    ub, hb = owner(client)
    shared_id = str(uuid.uuid4())
    assert put(client, ha, [ev("lesson_completed", "LSN-0001", shared_id)]).json()["results"][0]["status"] == "accepted"
    assert put(client, hb, [ev("lesson_opened", "LSN-0002", shared_id)]).json()["results"][0]["status"] == "accepted"  # same uuid, other user
    assert client.get("/v1/progress", headers=ha).json()["completed"] == ["LSN-0001"]
    pb = client.get("/v1/progress", headers=hb).json()
    assert pb["completed"] == [] and pb["resume"]["lesson_id"] == "LSN-0002"


def test_events_for_another_edition_are_not_accepted_and_foreign_editions_are_not_selectable(client):
    import_fixture("ed1")
    import_fixture("ed2", "0.2.0", mutate=lambda src: None)
    u, h = owner(client, "ed1")
    assert client.get("/v1/progress?edition=ed2", headers=h).status_code == 403
    assert put(client, h, [ev("lesson_opened", "LSN-0001")], edition="ed2").status_code == 403


def test_resume_position_logic(client):
    import_fixture()
    _, h = owner(client)
    first = client.get("/v1/progress", headers=h).json()
    assert first["resume"]["lesson_id"] == "LSN-0001" and first["resume"]["reason"] == "start" and first["total_lessons"] == 3 and not first["finished"]
    put(client, h, [ev("lesson_opened", "LSN-0002")])
    assert client.get("/v1/progress", headers=h).json()["resume"] == {"lesson_id": "LSN-0002", "title": "Changing a variable", "unit_id": "UNT-0001", "topic_id": "TOP-0001", "reason": "in_progress"}
    put(client, h, [ev("lesson_completed", "LSN-0002")])
    p = client.get("/v1/progress", headers=h).json()
    assert p["completed"] == ["LSN-0002"] and p["resume"]["lesson_id"] == "LSN-0003" and p["resume"]["reason"] == "next"
    put(client, h, [ev("lesson_completed", "LSN-0003")])
    assert client.get("/v1/progress", headers=h).json()["resume"]["lesson_id"] == "LSN-0001"  # a skipped lesson is still offered
    put(client, h, [ev("lesson_completed", "LSN-0001")])
    done = client.get("/v1/progress", headers=h).json()
    assert done["finished"] and done["resume"] is None and done["completed"] == ["LSN-0001", "LSN-0002", "LSN-0003"]


def test_same_event_sent_twice_at_once_is_accepted_exactly_once():
    import_fixture()
    with TestClient(app) as c:
        u, tok, h = signed_in(c)
        grant(u.id)
    eid = str(uuid.uuid4())
    barrier = threading.Barrier(8)

    def go(_):
        with TestClient(app) as c2:
            barrier.wait()
            return put(c2, h, [ev("lesson_completed", "LSN-0001", eid)]).json()["results"][0]["status"]

    with ThreadPoolExecutor(8) as ex:
        out = sorted(ex.map(go, range(8)))
    assert out == ["accepted"] + ["duplicate"] * 7
