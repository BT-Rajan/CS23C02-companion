import pytest
from fastapi.routing import APIRoute
from sqlalchemy import text
from sqlalchemy.exc import DBAPIError

from app.config import Settings, get_settings
from app.db import SessionLocal
from app.deps import get_current
from app.main import app
from app.security import validate_password
from tests.conftest import ip_headers, signed_in

PUBLIC = {"/v1/auth/register", "/v1/auth/verify-email", "/v1/auth/login", "/v1/auth/refresh", "/v1/auth/forgot",
          "/v1/auth/reset", "/healthz", "/docs", "/openapi.json", "/docs/oauth2-redirect"}


def _deps(dep):
    yield dep.call
    for d in dep.dependencies:
        yield from _deps(d)


def test_every_route_is_explicitly_public_or_requires_a_session():
    """A new route that is neither listed as public nor behind get_current fails this test (attack B7/F1)."""
    unprotected = []
    for r in app.routes:
        if isinstance(r, APIRoute) and r.path not in PUBLIC and get_current not in set(_deps(r.dependant)):
            unprotected.append(r.path)
    assert not unprotected, unprotected


def test_runtime_database_user_cannot_alter_or_delete_the_audit_log_or_the_schema(client):
    signed_in(client)  # writes audit rows
    with SessionLocal() as db:
        assert db.execute(text("SELECT COUNT(*) FROM audit_log")).scalar() > 0
    for sql in ("UPDATE audit_log SET outcome='x'", "DELETE FROM audit_log", "TRUNCATE TABLE audit_log",
                "DROP TABLE users", "ALTER TABLE users ADD COLUMN x INT", "SELECT * FROM alembic_version"):
        with SessionLocal() as db, pytest.raises(DBAPIError):
            db.execute(text(sql))


BASE = dict(_env_file=None, database_url="mysql+pymysql://u:p@h/db", jwt_secret="a" * 40, code_pepper="b" * 40, aux_pepper="c" * 40)


def test_settings_refuse_weak_placeholder_or_duplicate_secrets():
    for bad in ({"jwt_secret": "short"}, {"jwt_secret": "change-me-" + "x" * 40}, {"code_pepper": "a" * 40}):
        with pytest.raises(ValueError):
            Settings(**{**BASE, **bad})


def test_production_refuses_dev_mail_backends_and_cheap_password_hashing():
    prod = {**BASE, "env": "prod", "argon_memory_kb": 65536, "argon_time_cost": 3, "email_backend": "smtp", "smtp_user": "u@gmail.com", "smtp_password": "pw", "smtp_from": "u@gmail.com",
            "sandbox_mode": "http", "sandbox_url": "https://sandbox.internal", "sandbox_key": "ef" * 32}
    Settings(**prod)
    for bad in ({"email_backend": "console"}, {"email_backend": "memory"}, {"smtp_password": ""},
                {"argon_memory_kb": 8192}, {"argon_time_cost": 1},
                {"sandbox_mode": "local"},                       # student code never runs inside the API process in prod
                {"sandbox_key": "short"}, {"sandbox_url": ""}):
        with pytest.raises(ValueError):
            Settings(**{**prod, **bad})


def test_production_defaults_meet_the_password_hashing_floor():
    f = Settings.model_fields
    assert f['argon_memory_kb'].default >= 65536 and f['argon_time_cost'].default >= 3
    s = Settings(**BASE)
    assert s.max_sessions == 3 and s.access_ttl_seconds <= 900


def test_password_rules():
    validate_password("a perfectly fine one", "x@y.z")
    for bad in ("short", "password123", "x@y.z", "x" * 129):
        with pytest.raises(ValueError):
            validate_password(bad, "x@y.z")
