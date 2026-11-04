import time
from datetime import timedelta

import jwt
from sqlalchemy import select, update

from app import mail
from app.config import get_settings
from app.db import SessionLocal, utcnow
from app.models import AuditLog, OneTimeCode, User, UserSession
from tests.conftest import PW, ip_headers, last_code, login, make_user, signed_in


def register(client, email, pw=PW, headers=None):
    return client.post("/v1/auth/register", json={"email": email, "password": pw}, headers=headers or ip_headers())


def test_full_first_time_flow_register_verify_use_token(client):
    assert register(client, "new@test.dev").status_code == 202
    r = client.post("/v1/auth/verify-email", json={"email": "new@test.dev", "code": last_code("new@test.dev")}, headers=ip_headers())
    assert r.status_code == 200
    me = client.get("/v1/me", headers={"Authorization": "Bearer " + r.json()["access_token"]})
    assert me.status_code == 200 and me.json() == {"email": "new@test.dev", "entitlements": []}


def test_password_is_stored_as_argon2id_and_codes_as_hashes(client):
    register(client, "h@test.dev")
    code = last_code("h@test.dev")
    with SessionLocal() as db:
        u = db.scalar(select(User).where(User.email == "h@test.dev"))
        assert u.pw_hash.startswith("$argon2id$") and PW not in u.pw_hash
        assert all(code not in o.code_hash for o in db.scalars(select(OneTimeCode)))


def test_register_does_not_reveal_whether_an_email_exists(client):
    make_user("taken@test.dev")
    a = register(client, "fresh@test.dev")
    b = register(client, "taken@test.dev")
    assert (a.status_code, a.json()) == (b.status_code, b.json())
    owner_mail = [m for m in mail.OUTBOX if m["to"] == "taken@test.dev"]
    assert len(owner_mail) == 1 and "You already have one" in owner_mail[0]["body"] and "code is" not in owner_mail[0]["body"]


def test_unverified_register_cannot_hijack_a_verified_account(client):
    u = make_user("victim@test.dev")
    register(client, "victim@test.dev", pw="attacker password 1")
    assert login(client, "victim@test.dev").status_code == 200
    assert login(client, "victim@test.dev", pw="attacker password 1").status_code == 401


def test_forgot_and_login_are_enumeration_safe(client):
    make_user("real@test.dev")
    a = client.post("/v1/auth/forgot", json={"email": "real@test.dev"}, headers=ip_headers())
    b = client.post("/v1/auth/forgot", json={"email": "ghost@test.dev"}, headers=ip_headers())
    assert (a.status_code, a.json()) == (b.status_code, b.json()) == (202, {"status": "code_sent"})
    assert any(m["to"] == "real@test.dev" for m in mail.OUTBOX) and not any(m["to"] == "ghost@test.dev" for m in mail.OUTBOX)
    wrong, ghost = login(client, "real@test.dev", pw="wrong password!!"), login(client, "ghost@test.dev")
    assert (wrong.status_code, wrong.json()) == (ghost.status_code, ghost.json())


def test_unverified_user_cannot_sign_in_and_gets_a_new_code(client):
    make_user("unv@test.dev", verified=False)
    r = login(client, "unv@test.dev")
    assert r.status_code == 403 and r.json()["error"]["code"] == "email_unverified"
    assert last_code("unv@test.dev")


def test_verify_code_burns_after_five_wrong_guesses(client):
    register(client, "b@test.dev")
    good = last_code("b@test.dev")
    bad = "000000" if good != "000000" else "111111"
    for _ in range(5):
        assert client.post("/v1/auth/verify-email", json={"email": "b@test.dev", "code": bad}, headers=ip_headers()).status_code == 400
    assert client.post("/v1/auth/verify-email", json={"email": "b@test.dev", "code": good}, headers=ip_headers()).status_code == 400


def test_codes_are_single_use_and_expire(client):
    register(client, "s@test.dev")
    code = last_code("s@test.dev")
    assert client.post("/v1/auth/verify-email", json={"email": "s@test.dev", "code": code}, headers=ip_headers()).status_code == 200
    assert client.post("/v1/auth/verify-email", json={"email": "s@test.dev", "code": code}, headers=ip_headers()).status_code == 400
    make_user("e@test.dev", verified=False)
    client.post("/v1/auth/forgot", json={"email": "e@test.dev"}, headers=ip_headers())
    c2 = last_code("e@test.dev")
    with SessionLocal() as db:
        db.execute(update(OneTimeCode).where(OneTimeCode.email == "e@test.dev").values(expires_at=utcnow() - timedelta(seconds=1)))
        db.commit()
    r = client.post("/v1/auth/reset", json={"email": "e@test.dev", "code": c2, "new_password": "another good pass"}, headers=ip_headers())
    assert r.status_code == 400


def test_new_code_invalidates_the_previous_one_and_sends_are_throttled(client):
    make_user("t@test.dev")
    client.post("/v1/auth/forgot", json={"email": "t@test.dev"}, headers=ip_headers())
    first = last_code("t@test.dev")
    client.post("/v1/auth/forgot", json={"email": "t@test.dev"}, headers=ip_headers())  # inside the 60 s cooldown: no new email
    assert len([m for m in mail.OUTBOX if m["to"] == "t@test.dev"]) == 1
    assert last_code("t@test.dev") == first


def test_password_reset_revokes_every_session_and_changes_the_password(client):
    u, tok, h = signed_in(client, "r@test.dev")
    other = login(client, "r@test.dev").json()
    client.post("/v1/auth/forgot", json={"email": "r@test.dev"}, headers=ip_headers())
    code = last_code("r@test.dev")
    weak = client.post("/v1/auth/reset", json={"email": "r@test.dev", "code": code, "new_password": "short"}, headers=ip_headers())
    assert weak.status_code == 422  # a weak password does not burn the code
    ok = client.post("/v1/auth/reset", json={"email": "r@test.dev", "code": code, "new_password": "brand new passphrase"}, headers=ip_headers())
    assert ok.status_code == 200
    assert client.get("/v1/me", headers=h).status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Bearer " + other["access_token"]}).status_code == 401
    assert client.post("/v1/auth/refresh", json={"refresh_token": tok["refresh_token"]}, headers=ip_headers()).status_code == 401
    assert login(client, "r@test.dev").status_code == 401
    assert login(client, "r@test.dev", pw="brand new passphrase").status_code == 200
    assert client.post("/v1/auth/reset", json={"email": "r@test.dev", "code": code, "new_password": "yet another passphrase"}, headers=ip_headers()).status_code == 400


def test_password_policy(client):
    for bad in ["short", "password123", "x" * 129, "r@test.dev"]:
        assert register(client, "r@test.dev", pw=bad).status_code in (422,)


def test_refresh_rotates_and_a_replayed_old_token_revokes_the_session(client):
    _, tok, h = signed_in(client)
    first = tok["refresh_token"]
    r2 = client.post("/v1/auth/refresh", json={"refresh_token": first}, headers=ip_headers())
    assert r2.status_code == 200 and r2.json()["refresh_token"] != first
    # grace period: a retry after a lost response is tolerated
    r3 = client.post("/v1/auth/refresh", json={"refresh_token": first}, headers=ip_headers())
    assert r3.status_code == 200
    latest = r3.json()["refresh_token"]
    with SessionLocal() as db:  # pretend the rotation happened a minute ago
        db.execute(update(UserSession).values(rotated_at=utcnow() - timedelta(seconds=60)))
        db.commit()
    stolen_replay = client.post("/v1/auth/refresh", json={"refresh_token": r2.json()["refresh_token"]}, headers=ip_headers())
    assert stolen_replay.status_code == 401
    assert client.post("/v1/auth/refresh", json={"refresh_token": latest}, headers=ip_headers()).status_code == 401  # family revoked
    assert client.get("/v1/me", headers=h).status_code == 401
    with SessionLocal() as db:
        assert db.scalar(select(AuditLog).where(AuditLog.action == "refresh_reuse")) is not None


def test_unknown_refresh_token_is_rejected(client):
    assert client.post("/v1/auth/refresh", json={"refresh_token": "x" * 43}, headers=ip_headers()).status_code == 401


def test_fourth_device_signs_out_the_oldest_and_never_blocks_the_new_one(client):
    u = make_user("d@test.dev")
    sessions = []
    for i in range(4):
        r = login(client, u.email, device=f"phone{i}")
        assert r.status_code == 200
        sessions.append({"Authorization": "Bearer " + r.json()["access_token"]})
        time.sleep(0.01)
    assert client.get("/v1/me", headers=sessions[0]).status_code == 401
    assert all(client.get("/v1/me", headers=h).status_code == 200 for h in sessions[1:])
    with SessionLocal() as db:
        assert db.scalar(select(UserSession.revoked_reason).where(UserSession.device_label == "phone0")) == "device_limit"


def test_logout_revokes_only_this_session(client):
    u = make_user("l@test.dev")
    a = {"Authorization": "Bearer " + login(client, u.email).json()["access_token"]}
    b = {"Authorization": "Bearer " + login(client, u.email).json()["access_token"]}
    assert client.post("/v1/auth/logout", headers=a).status_code == 204
    assert client.get("/v1/me", headers=a).status_code == 401 and client.get("/v1/me", headers=b).status_code == 200


def test_tampered_forged_and_wrong_type_tokens_are_rejected(client):
    u, tok, h = signed_in(client)
    s = get_settings()
    now = int(time.time())
    with SessionLocal() as db:
        sid, uid = db.scalar(select(UserSession.id)), u.id
    claims = {"sub": uid, "sid": sid, "typ": "access", "iat": now, "exp": now + 600}
    other_user = make_user()
    forged = {
        "alg none": jwt.encode(claims, key=None, algorithm="none"),
        "wrong key": jwt.encode(claims, "x" * 40, algorithm="HS256"),
        "expired": jwt.encode({**claims, "iat": now - 2000, "exp": now - 1000}, s.jwt_secret, algorithm="HS256"),
        "wrong type": jwt.encode({**claims, "typ": "refresh"}, s.jwt_secret, algorithm="HS256"),
        "other user's id on my session": jwt.encode({**claims, "sub": other_user.id}, s.jwt_secret, algorithm="HS256"),
        "unknown session": jwt.encode({**claims, "sid": "00000000-0000-0000-0000-000000000000"}, s.jwt_secret, algorithm="HS256"),
        "garbage": "not.a.jwt",
    }
    for name, t in forged.items():
        assert client.get("/v1/me", headers={"Authorization": "Bearer " + t}).status_code == 401, name
    flipped = tok["access_token"][:-3] + ("AAA" if not tok["access_token"].endswith("AAA") else "BBB")
    assert client.get("/v1/me", headers={"Authorization": "Bearer " + flipped}).status_code == 401
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me", headers=h).status_code == 200


def test_login_is_throttled_even_for_the_right_password(client):
    u = make_user("th@test.dev")
    ip = ip_headers("203.0.113.9")
    for _ in range(5):
        assert login(client, u.email, pw="wrong password!!", headers=ip).status_code == 401
    r = login(client, u.email, headers=ip)
    assert r.status_code == 429 and "Retry-After" in r.headers


def test_request_hygiene(client):
    big = client.post("/v1/auth/login", content=b"x" * 70000, headers={"Content-Type": "application/json", **ip_headers()})
    assert big.status_code == 413
    r = client.post("/v1/auth/login", json={"email": "a@test.dev", "password": "SECRET-PW-VALUE", "admin": True}, headers=ip_headers())
    assert r.status_code == 422 and "SECRET-PW-VALUE" not in r.text
    assert r.headers["cache-control"] == "no-store" and r.headers["x-content-type-options"] == "nosniff"
