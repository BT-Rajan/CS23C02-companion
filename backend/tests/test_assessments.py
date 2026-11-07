"""A7 unit assessments: server-owned state machine (ADR 0009; attacks D1, D3, D4, D5, D6, C2)."""
import json
import uuid
from datetime import timedelta

from sqlalchemy import update

from app.db import SessionLocal, utcnow
from app.models import AssessmentAttempt
from tests.conftest import import_fixture, owner, signed_in

ASM = "ASM-0002"
KEY = {"ITM-0005": "a", "ITM-0006": "b", "ITM-0007": "b"}   # fixture answers (the server knows these; the client never does)


def start(client, h, asm=ASM):
    return client.post(f"/v1/assessments/{asm}/attempts", json={}, headers=h)


def save(client, h, att, seq, answers):
    return client.put(f"/v1/assessments/attempts/{att}/answers", json={"seq": seq, "answers": answers}, headers=h)


def submit(client, h, att, rid=None, **extra):
    return client.post(f"/v1/assessments/attempts/{att}/submit", json={"request_id": rid or str(uuid.uuid4()), **extra}, headers=h)


def test_full_attempt_scores_on_the_server_and_releases_the_review_only_after_submit(client):
    import_fixture()
    _, h = owner(client)
    r = start(client, h)
    assert r.status_code == 200, r.text
    a = r.json()
    assert a["status"] == "in_progress" and a["title"] == "Unit 1 assessment" and len(a["questions"]) == 3
    assert {q["item_id"] for q in a["questions"]} == set(KEY)
    trace = next(q for q in a["questions"] if q["type"] == "trace")
    assert "print(x)" in trace["code"]
    before = json.dumps(a)
    assert '"answer"' not in before and "explanation" not in before and "FIXTURE-EXPLAIN" not in before and "correct" not in before
    att = a["attempt_id"]
    answers = dict(KEY); answers["ITM-0006"] = "a"   # two right, one wrong
    assert save(client, h, att, 1, answers).json() == {"attempt_id": att, "seq": 1, "saved": 3}
    view = client.get(f"/v1/assessments/attempts/{att}", headers=h).json()
    assert view["answers"] == answers and "review" not in view and "FIXTURE-EXPLAIN" not in json.dumps(view)
    res = submit(client, h, att).json()
    assert res["status"] == "submitted" and (res["score"], res["max_score"]) == (2, 3)
    wrong = next(x for x in res["review"] if x["item_id"] == "ITM-0006")
    assert wrong["correct"] is False and wrong["chosen"] == "a" and wrong["answer"] == "b" and wrong["why"] == "Starts with a digit."
    assert wrong["explanation"].startswith("FIXTURE-EXPLAIN-6")
    assert client.get(f"/v1/assessments/attempts/{att}", headers=h).json()["review"] == res["review"]   # review later


def test_starting_twice_resumes_the_same_attempt(client):
    import_fixture()
    _, h = owner(client)
    a, b = start(client, h).json(), start(client, h).json()
    assert a["attempt_id"] == b["attempt_id"] and a["questions"] == b["questions"]


def test_d6_options_are_shuffled_per_attempt_and_stable_within_it(client):
    import_fixture()
    layouts = set()
    for _ in range(6):
        _, h = owner(client)
        q = start(client, h).json()["questions"]
        layouts.add(json.dumps([(x["item_id"], [o["id"] for o in x["options"]]) for x in q]))
    assert len(layouts) > 1


def test_d3_the_client_cannot_claim_a_score_or_answer_outside_the_attempt(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    r = client.put(f"/v1/assessments/attempts/{att}/answers", json={"seq": 1, "answers": KEY, "score": 3}, headers=h)
    assert r.status_code == 422
    assert save(client, h, att, 1, {"ITM-0001": "b"}).status_code == 422       # a practice item is not in this attempt
    assert save(client, h, att, 1, {"ITM-0005": "z"}).status_code == 422       # not one of its options
    r = client.post(f"/v1/assessments/attempts/{att}/submit", json={"request_id": str(uuid.uuid4()), "score": 3}, headers=h)
    assert r.status_code == 422


def test_autosave_order_is_enforced(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    assert save(client, h, att, 2, {"ITM-0005": "a"}).status_code == 200
    stale = save(client, h, att, 1, {"ITM-0005": "b"})                         # an older save arriving late
    assert stale.status_code == 409 and stale.headers["X-Current-Seq"] == "2"
    assert client.get(f"/v1/assessments/attempts/{att}", headers=h).json()["answers"] == {"ITM-0005": "a"}


def test_d4_submit_is_idempotent_and_saving_after_submit_is_refused(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    save(client, h, att, 1, KEY)
    rid = str(uuid.uuid4())
    first = submit(client, h, att, rid).json()
    again = submit(client, h, att, rid).json()
    other = submit(client, h, att, answers={k: "a" for k in KEY}, seq=9).json()   # replay with different answers
    assert first["score"] == again["score"] == other["score"] == 3 and first["submitted_at"] == other["submitted_at"]
    assert save(client, h, att, 5, {"ITM-0005": "b"}).json()["error"]["code"] == "already_submitted"


def test_final_answers_can_ride_on_submit(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    save(client, h, att, 1, {"ITM-0005": "a"})
    res = submit(client, h, att, seq=2, answers=KEY).json()
    assert res["score"] == 3


def test_d5_one_attempt_per_24_hours_is_enforced_by_the_server(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    submit(client, h, att)
    r = start(client, h)
    assert r.status_code == 409 and r.json()["error"]["code"] == "attempt_cooldown" and "X-Next-Attempt-At" in r.headers
    hist = client.get(f"/v1/assessments/{ASM}/attempts", headers=h).json()
    assert hist["next_attempt_at"] is not None and len(hist["attempts"]) == 1
    with SessionLocal() as db:   # 25 hours later
        db.execute(update(AssessmentAttempt).values(submitted_at=utcnow() - timedelta(hours=25)))
        db.commit()
    assert start(client, h).status_code == 200


def test_an_expired_attempt_is_scored_from_its_saved_answers(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    save(client, h, att, 1, {"ITM-0005": "a", "ITM-0007": "b"})
    with SessionLocal() as db:   # 3 h 10 min later
        db.execute(update(AssessmentAttempt).values(expires_at=utcnow() - timedelta(minutes=10)))
        db.commit()
    assert save(client, h, att, 2, KEY).json()["error"]["code"] == "already_submitted"
    v = client.get(f"/v1/assessments/attempts/{att}", headers=h).json()
    assert v["status"] == "submitted" and v["auto_submitted"] is True and v["score"] == 2


def test_c2_another_students_attempt_looks_like_a_missing_one(client):
    import_fixture()
    _, h1 = owner(client)
    _, h2 = owner(client)
    att = start(client, h1).json()["attempt_id"]
    for r in (client.get(f"/v1/assessments/attempts/{att}", headers=h2), save(client, h2, att, 1, KEY), submit(client, h2, att),
              client.get(f"/v1/assessments/attempts/{uuid.uuid4()}", headers=h2)):
        assert r.status_code == 404 and r.json()["error"]["code"] == "not_found"


def test_access_rules(client):
    import_fixture()
    assert start(client, {}).status_code == 401
    _, _, h = signed_in(client)
    assert start(client, h).status_code == 403                       # no book activated
    _, h = owner(client)
    assert start(client, h, "ASM-0001").status_code == 404          # a lesson quiz is not a unit assessment
    assert start(client, h, "ASM-9999").status_code == 404
    assert client.get("/v1/assessments/attempts/not-a-uuid", headers=h).status_code == 422


def test_assessment_items_are_never_checkable_as_practice(client):
    import_fixture()
    _, h = owner(client)
    for item in KEY:
        r = client.post(f"/v1/practice/items/{item}/answer", json={"option": "a"}, headers=h)
        assert r.status_code == 404


def test_progress_reports_the_best_score(client):
    import_fixture()
    _, h = owner(client)
    att = start(client, h).json()["attempt_id"]
    save(client, h, att, 1, KEY)
    submit(client, h, att)
    assert client.get("/v1/progress", headers=h).json()["assessments"] == [
        {"assessment_id": ASM, "best_score": 3, "max_score": 3, "attempts": 1}]


def test_simultaneous_starts_create_exactly_one_attempt(client):
    import concurrent.futures
    from sqlalchemy import func, select
    import_fixture()
    u, h = owner(client)
    with concurrent.futures.ThreadPoolExecutor(8) as ex:
        ids = {r.json()["attempt_id"] for r in ex.map(lambda _: start(client, h), range(8))}
    with SessionLocal() as db:
        n = db.scalar(select(func.count()).select_from(AssessmentAttempt).where(AssessmentAttempt.user_id == u.id))
    assert n == 1 and len(ids) == 1
