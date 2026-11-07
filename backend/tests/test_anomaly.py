"""K9 / attack B1: anomaly re-verification for credential sharing (ADR 0004). Deter and detect; never lock."""
import secrets
from datetime import timedelta

import pytest

from sqlalchemy import func, select, update

from app import mail
from app.cli import clear_flag, flagged_accounts
from app.db import SessionLocal, utcnow
from app.models import AuditLog, User, UserSession
from tests.conftest import ensure_edition, grant, import_fixture, ip_headers, last_code, login
from tests.conftest import make_user as _make_user

PROTECTED = "/v1/progress"


@pytest.fixture(autouse=True)
def published(client):
    import_fixture()


def make_user():
    """A verified user who has activated the book, so protected routes answer 200 unless re-verification is due."""
    u = _make_user()
    ensure_edition("ed1")
    grant(u.id, "ed1")
    return u


def bearer(tok):
    return {"Authorization": "Bearer " + tok["access_token"]}


def signins(client, email, n, ip=None):
    out = []
    for i in range(n):
        r = login(client, email, device=f"device {i}", headers=ip_headers(ip))
        assert r.status_code == 200, r.text
        out.append(r.json())
    return out


def anomalies(uid):
    with SessionLocal() as db:
        return db.scalar(select(func.count()).select_from(AuditLog).where(AuditLog.actor_id == uid, AuditLog.action == "anomaly_detected"))


def test_ordinary_use_on_three_devices_never_triggers(client):
    u = make_user()
    toks = signins(client, u.email, 3, ip="10.1.1.1")
    assert all(t["reverify_required"] is False for t in toks)
    for t in toks:
        assert client.get(PROTECTED, headers=bearer(t)).status_code == 200
    assert client.get("/v1/me", headers=bearer(toks[0])).json()["reverify_required"] is False
    assert anomalies(u.id) == 0


def test_many_addresses_trigger_reverification_on_every_session_without_locking(client):
    u = make_user()
    toks = signins(client, u.email, 4)              # four networks in a day: friends sharing one account
    assert [t["reverify_required"] for t in toks] == [False, False, False, True]
    live = toks[1:]                                  # the device cap already pushed the first one out
    for t in live:
        r = client.get(PROTECTED, headers=bearer(t))
        assert r.status_code == 403 and r.json()["error"]["code"] == "reverify_required"
        me = client.get("/v1/me", headers=bearer(t))
        assert me.status_code == 200 and me.json()["reverify_required"] is True
    assert anomalies(u.id) == 1
    assert client.post("/v1/auth/logout", headers=bearer(live[0])).status_code == 204   # signing out works
    assert login(client, u.email).status_code == 200                       # and so does signing in: never a lock


def test_each_session_confirms_with_its_own_code_sent_to_the_account_address(client):
    u = make_user()
    a, b = signins(client, u.email, 4)[2:]
    mail.OUTBOX.clear()
    r = client.post("/v1/auth/reverify/send", headers=bearer(a))
    assert r.status_code == 202 and r.json() == {"status": "code_sent", "email_hint": u.email[0] + "***@test.dev"}
    assert [m["to"] for m in mail.OUTBOX] == [u.email]
    code = last_code(u.email)
    assert client.post("/v1/auth/reverify", json={"code": "000000" if code != "000000" else "111111"}, headers=bearer(a)).status_code == 400
    assert client.post("/v1/auth/reverify", json={"code": code}, headers=bearer(a)).json() == {"status": "ok"}
    assert client.get(PROTECTED, headers=bearer(a)).status_code == 200
    assert client.get(PROTECTED, headers=bearer(b)).status_code == 403            # per session, not per account
    assert client.post("/v1/auth/reverify", json={"code": code}, headers=bearer(b)).status_code == 400   # single use


def test_refresh_keeps_the_session_and_reports_the_requirement(client):
    u = make_user()
    t = signins(client, u.email, 4)[-1]
    r = client.post("/v1/auth/refresh", json={"refresh_token": t["refresh_token"]}, headers=ip_headers())
    assert r.status_code == 200 and r.json()["reverify_required"] is True


def test_many_signins_from_one_address_and_repeated_evictions_also_trigger(client):
    u = make_user()
    toks = signins(client, u.email, 6, ip="10.9.9.9")      # one hostel NAT, six sign-ins in a day
    assert toks[-1]["reverify_required"] is True and toks[-2]["reverify_required"] is False
    v = make_user()
    old = utcnow() - timedelta(days=3)
    with SessionLocal() as db:                              # friends taking turns over the week (outside the 24 h window)
        for i in range(4):
            db.add(UserSession(user_id=v.id, refresh_hash=secrets.token_hex(32), device_label="x", created_at=old, last_used_at=old,
                               revoked_at=old, revoked_reason="device_limit", ip_hash="0" * 16))
        db.commit()
    assert signins(client, v.email, 1)[0]["reverify_required"] is True
    with SessionLocal() as db:
        assert db.scalar(select(AuditLog.object).where(AuditLog.actor_id == v.id, AuditLog.action == "anomaly_detected")) == "evictions_7d"


def test_no_retrigger_while_the_window_is_open_and_new_devices_after_it_are_free(client):
    u = make_user()
    toks = signins(client, u.email, 5)
    assert anomalies(u.id) == 1 and toks[4]["reverify_required"] is True     # created inside the window: must confirm
    with SessionLocal() as db:                                                  # eight days later
        db.execute(update(User).where(User.id == u.id).values(reverify_since=utcnow() - timedelta(days=8)))
        db.execute(update(UserSession).where(UserSession.user_id == u.id).values(created_at=utcnow() - timedelta(days=8)))
        db.commit()
    fresh = signins(client, u.email, 1, ip="10.7.7.7")[0]
    assert fresh["reverify_required"] is False and client.get(PROTECTED, headers=bearer(fresh)).status_code == 200
    assert client.get(PROTECTED, headers=bearer(toks[4])).status_code == 403  # an unconfirmed old session still must confirm


def test_repeated_triggers_raise_a_support_flag_for_a_person(client, capsys):
    u = make_user()
    for round_ in range(3):
        signins(client, u.email, 4)
        with SessionLocal() as db:                       # let the window lapse so the next burst can trigger again
            db.execute(update(User).where(User.id == u.id).values(reverify_since=utcnow() - timedelta(days=8)))
            db.execute(update(UserSession).where(UserSession.user_id == u.id).values(created_at=utcnow() - timedelta(days=2)))
            db.commit()
    assert anomalies(u.id) == 3
    with SessionLocal() as db:
        assert db.get(User, u.id).support_flag_at is not None
        assert db.scalar(select(func.count()).select_from(AuditLog).where(AuditLog.actor_id == u.id, AuditLog.action == "support_flag")) == 1
    flagged_accounts(None)
    assert u.email in capsys.readouterr().out
    clear_flag(type("A", (), {"email": u.email, "note": "spoke to the owner"}))
    with SessionLocal() as db:
        assert db.get(User, u.id).support_flag_at is None


def test_reverify_endpoints_need_a_session_and_say_when_not_needed(client):
    assert client.post("/v1/auth/reverify/send").status_code == 401
    assert client.post("/v1/auth/reverify", json={"code": "123456"}).status_code == 401
    u = make_user()
    t = signins(client, u.email, 1)[0]
    mail.OUTBOX.clear()
    assert client.post("/v1/auth/reverify/send", headers=bearer(t)).json() == {"status": "not_needed"}
    assert mail.OUTBOX == []


def test_content_is_gated_too(client):
    u = make_user()
    t = signins(client, u.email, 4)[-1]
    r = client.get("/v1/content/manifest", headers=bearer(t))
    assert r.status_code == 403 and r.json()["error"]["code"] == "reverify_required"


def test_a_confirmed_device_must_confirm_again_after_a_later_trigger(client):
    u = make_user()
    t = signins(client, u.email, 4)[-1]
    client.post("/v1/auth/reverify/send", headers=bearer(t))
    assert client.post("/v1/auth/reverify", json={"code": last_code(u.email)}, headers=bearer(t)).status_code == 200
    with SessionLocal() as db:                       # the window lapses; the confirmed device keeps working
        db.execute(update(User).where(User.id == u.id).values(reverify_since=utcnow() - timedelta(days=8)))
        db.execute(update(UserSession).where(UserSession.user_id == u.id).values(
            created_at=utcnow() - timedelta(days=9), reverified_at=utcnow() - timedelta(days=8)))
        db.commit()
    assert client.get(PROTECTED, headers=bearer(t)).status_code == 200
    old = utcnow() - timedelta(days=2)
    with SessionLocal() as db:                       # sharing starts again (seen as device-cap evictions this week)
        for _ in range(4):
            db.add(UserSession(user_id=u.id, refresh_hash=secrets.token_hex(32), device_label="x", created_at=old,
                               last_used_at=old, revoked_at=old, revoked_reason="device_limit"))
        db.commit()
    signins(client, u.email, 1)                      # one more sign-in: a new trigger; the confirmed device is still signed in
    assert anomalies(u.id) == 2
    r = client.get(PROTECTED, headers=bearer(t))
    assert r.status_code == 403 and r.json()["error"]["code"] == "reverify_required"   # it must confirm again
