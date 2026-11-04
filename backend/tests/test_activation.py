import os
import stat
import threading
from argparse import Namespace
from concurrent.futures import ThreadPoolExecutor

from fastapi.testclient import TestClient
from sqlalchemy import func, select

from app import cli
from app.config import get_settings
from app.activation import codes as codelib
from app.activation.service import redeem
from app.db import SessionLocal
from app.errors import ApiError
from app.main import app
from app.models import ActivationCode, AuditLog, Entitlement
from tests.conftest import ip_headers, login, make_user, seed_codes, signed_in


def activate(client, headers, code, extra=None):
    return client.post("/v1/activation", json={"code": code, **(extra or {})}, headers={**headers, **ip_headers()})


def state_of(code):
    from app.activation.service import code_hmac
    with SessionLocal() as db:
        return db.scalar(select(ActivationCode.state).where(ActivationCode.code_hmac == code_hmac(code)))


def entitlements():
    with SessionLocal() as db:
        return db.scalar(select(func.count()).select_from(Entitlement))


def test_successful_activation_grants_the_edition_of_the_code(client):
    _, _, h = signed_in(client)
    (code,) = seed_codes(1, edition="ed-A", title="Edition A")
    r = activate(client, h, codelib.pretty(code))
    assert r.status_code == 200 and r.json()["edition_id"] == "ed-A"
    assert client.get("/v1/me", headers=h).json()["entitlements"][0]["title"] == "Edition A"
    assert state_of(code) == "redeemed"


def test_the_database_never_holds_a_plaintext_code(client):
    _, _, h = signed_in(client)
    (code,) = seed_codes(1)
    activate(client, h, code)
    with SessionLocal() as db:
        dump = " ".join(str(v) for t in (ActivationCode, AuditLog) for row in db.scalars(select(t)) for v in row.__dict__.values())
    assert code not in dump and codelib.pretty(code) not in dump


def test_reused_unissued_revoked_and_unknown_codes_are_indistinguishable(client):
    _, _, h = signed_in(client)
    used, unissued, revoked = seed_codes(1)[0], seed_codes(1, "unissued")[0], seed_codes(1, "revoked")[0]
    assert activate(client, h, used).status_code == 200
    _, _, h2 = signed_in(client)
    results = [activate(client, h2, c) for c in (used, unissued, revoked, codelib.generate())]
    assert {r.status_code for r in results} == {400}
    assert len({r.json()["error"]["message"] for r in results}) == 1  # no oracle for which case it was
    assert all(r.json()["error"]["ref"] for r in results)


def test_second_code_for_an_owned_edition_is_refused_and_not_consumed(client):
    _, _, h = signed_in(client)
    a, b = seed_codes(2)
    assert activate(client, h, a).status_code == 200
    r = activate(client, h, b)
    assert r.status_code == 409 and r.json()["error"]["code"] == "already_activated"
    assert state_of(b) == "issued"  # the unused book's code is still good for its real owner
    _, _, h2 = signed_in(client)
    assert activate(client, h2, b).status_code == 200


def test_typo_is_caught_by_the_check_character(client):
    _, _, h = signed_in(client)
    (code,) = seed_codes(1)
    typo = ("A" if code[0] != "A" else "B") + code[1:]
    r = activate(client, h, typo)
    assert r.status_code == 400 and r.json()["error"]["code"] == "bad_format" and state_of(code) == "issued"


def test_entry_is_forgiving_about_case_spaces_hyphens(client):
    _, _, h = signed_in(client)
    (code,) = seed_codes(1)
    assert activate(client, h, " " + codelib.pretty(code).lower().replace("-", " - ") + " ").status_code == 200


def test_client_cannot_choose_edition_or_user(client):
    _, _, h = signed_in(client)
    (code,) = seed_codes(1)
    assert activate(client, h, code, {"edition_id": "ed-evil", "user_id": "x"}).status_code == 422
    assert state_of(code) == "issued" and entitlements() == 0


def test_activation_needs_a_valid_session(client):
    (code,) = seed_codes(1)
    assert client.post("/v1/activation", json={"code": code}).status_code == 401
    assert client.post("/v1/activation", json={"code": code}, headers={"Authorization": "Bearer junk"}).status_code == 401
    assert state_of(code) == "issued"


def test_account_is_locked_out_of_guessing_after_five_failures_even_with_a_real_code(client):
    _, _, h = signed_in(client)
    (real,) = seed_codes(1)
    for _ in range(5):
        assert activate(client, h, codelib.generate()).status_code == 400
    r = activate(client, h, real)
    assert r.status_code == 429 and state_of(real) == "issued"


def test_one_ip_cannot_guess_across_many_accounts(client, monkeypatch):
    monkeypatch.setattr(get_settings(), 'activation_ip_fail_limit', 20)
    ip = {"X-Forwarded-For": "198.51.100.7"}
    users = [make_user() for _ in range(6)]
    heads = [{"Authorization": "Bearer " + login(client, u.email).json()["access_token"]} for u in users]
    for h in heads[:5]:
        for _ in range(4):
            assert client.post("/v1/activation", json={"code": codelib.generate()}, headers={**h, **ip}).status_code == 400
    (real,) = seed_codes(1)
    assert client.post("/v1/activation", json={"code": real}, headers={**heads[5], **ip}).status_code == 429


def test_audit_trail_records_outcomes_with_the_reference_shown_to_the_student(client):
    u, _, h = signed_in(client)
    (code,) = seed_codes(1)
    bad = activate(client, h, codelib.generate())
    ok = activate(client, h, code)
    with SessionLocal() as db:
        rows = {(a.outcome, a.ref) for a in db.scalars(select(AuditLog).where(AuditLog.action == "activation", AuditLog.actor_id == u.id))}
    assert ("fail_unknown", bad.json()["error"]["ref"]) in rows
    assert any(o == "success" for o, _ in rows)


def test_concurrent_redemption_of_one_code_has_exactly_one_winner():
    for round_ in range(3):
        (code,) = seed_codes(1)
        users = [make_user() for _ in range(30)]
        barrier, results = threading.Barrier(30), []

        def go(u):
            barrier.wait()
            with SessionLocal() as db:
                try:
                    redeem(db, u.id, code, "ip-" + u.id[:8])
                    return ("ok", u.id)
                except ApiError as e:
                    return (e.code, u.id)

        with ThreadPoolExecutor(30) as ex:
            results = list(ex.map(go, users))
        winners = [uid for r, uid in results if r == "ok"]
        assert len(winners) == 1, results
        assert all(r in ("activation_failed",) for r, _ in results if r != "ok"), results
        assert entitlements() == round_ + 1
        with SessionLocal() as db:
            from app.activation.service import code_hmac
            assert db.scalar(select(ActivationCode.redeemed_by).where(ActivationCode.code_hmac == code_hmac(code))) == winners[0]


def test_concurrent_redemption_through_the_http_api():
    (code,) = seed_codes(1)
    users = [make_user() for _ in range(12)]
    heads = []
    with TestClient(app) as c:
        for u in users:
            heads.append({"Authorization": "Bearer " + login(c, u.email).json()["access_token"]})
    barrier = threading.Barrier(12)

    def go(h):
        with TestClient(app) as c:
            barrier.wait()
            return c.post("/v1/activation", json={"code": code}, headers={**h, **ip_headers()}).status_code

    with ThreadPoolExecutor(12) as ex:
        codes_ = sorted(ex.map(go, heads))
    assert codes_ == [200] + [400] * 11 and entitlements() == 1


def test_same_account_racing_two_codes_for_one_edition_keeps_only_one():
    for _ in range(8):
        a, b = seed_codes(2)
        u = make_user()
        barrier = threading.Barrier(2)

        def go(code):
            barrier.wait()
            with SessionLocal() as db:
                try:
                    redeem(db, u.id, code, "ip-" + u.id[:8])
                    return "ok"
                except ApiError as e:
                    return e.code

        with ThreadPoolExecutor(2) as ex:
            res = sorted(ex.map(go, [a, b]))
        assert res == ["already_activated", "ok"], res
        assert sorted([state_of(a), state_of(b)]) == ["issued", "redeemed"]
        with SessionLocal() as db:  # tidy so the loop's per-user count stays meaningful
            db.query(Entitlement).delete()
            db.commit()


def test_cli_batch_lifecycle(tmp_path):
    cli.create_edition(Namespace(id="ed-cli", title="CLI edition"))
    out = tmp_path / "codes.csv"
    cli.create_batch(Namespace(edition="ed-cli", count=25, label="print run 1", out=str(out)))
    assert stat.S_IMODE(os.stat(out).st_mode) == 0o600
    lines = out.read_text().strip().splitlines()[1:]
    plain = [l.split(",")[1] for l in lines]
    assert len(plain) == 25 and all(codelib.checksum_ok(codelib.normalise(p)) for p in plain)
    with SessionLocal() as db:
        assert set(db.scalars(select(ActivationCode.state))) == {"unissued"}
    client = TestClient(app)
    _, _, h = signed_in(client)
    assert activate(client, h, plain[0]).status_code == 400  # not yet released from print
    bid = int(lines[0].split(",")[0].split("-")[0])
    cli._set_state(Namespace(batch=bid), ["unissued"], "issued")
    assert activate(client, h, plain[0]).status_code == 200
    cli._set_state(Namespace(batch=bid), ["unissued", "issued"], "revoked")  # a leaked batch is contained...
    _, _, h2 = signed_in(client)
    assert activate(client, h2, plain[1]).status_code == 400
    assert state_of(codelib.normalise(plain[0])) == "redeemed"  # ...without touching books already activated
