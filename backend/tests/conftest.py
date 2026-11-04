import os
import pathlib
import secrets

# Settings must be in place before the app is imported.
os.environ.update({
    "ENV": "test", "EMAIL_BACKEND": "memory", "TRUST_PROXY_HEADERS": "true",
    "ARGON_MEMORY_KB": "8192", "ARGON_TIME_COST": "1", "DB_POOL_SIZE": "40", "DB_MAX_OVERFLOW": "20",
    "JWT_SECRET": secrets.token_hex(32), "CODE_PEPPER": secrets.token_hex(32), "AUX_PEPPER": secrets.token_hex(32),
})
os.environ.setdefault("DATABASE_URL", "mysql+pymysql://app_rt:app_rt_test_pw@127.0.0.1/cs23c02_test?charset=utf8mb4")
os.environ.setdefault("MIGRATION_DATABASE_URL", "mysql+pymysql://migrator:migrator_test_pw@127.0.0.1/cs23c02_test?charset=utf8mb4")
os.environ.setdefault("TEST_ADMIN_URL", "mysql+pymysql://root@localhost/?unix_socket=/var/run/mysqld/mysqld.sock")

import re  # noqa: E402

import pytest  # noqa: E402
from alembic import command  # noqa: E402
from alembic.config import Config  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402
from sqlalchemy import create_engine, text  # noqa: E402

from app import mail  # noqa: E402
from app.activation import codes as codelib  # noqa: E402
from app.activation.service import code_hmac  # noqa: E402
from app.cli import grants_sql  # noqa: E402
from app.db import SessionLocal, utcnow  # noqa: E402
from app.main import app  # noqa: E402
from app.models import ActivationBatch, ActivationCode, Base, Edition, User  # noqa: E402
from app.security import hash_password  # noqa: E402

BACKEND = pathlib.Path(__file__).resolve().parents[1]
MIG = create_engine(os.environ["MIGRATION_DATABASE_URL"])
APP_HOST = os.environ.get('TEST_APP_USER_HOST', 'localhost')
ADMIN = create_engine(os.environ["TEST_ADMIN_URL"])
PW = "correct horse battery"


@pytest.fixture(scope="session", autouse=True)
def schema():
    """Build the schema with the real migrations, then grant the runtime user only what production would."""
    with MIG.begin() as c:
        c.execute(text("SET FOREIGN_KEY_CHECKS=0"))
        for (t,) in c.execute(text("SHOW TABLES")).all():
            c.execute(text(f"DROP TABLE `{t}`"))
        c.execute(text("SET FOREIGN_KEY_CHECKS=1"))
    cfg = Config(str(BACKEND / "alembic.ini"))
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    command.upgrade(cfg, "head")
    with ADMIN.begin() as c:
        c.execute(text(f"REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'app_rt'@'{APP_HOST}'"))
        c.execute(text(f"GRANT USAGE ON *.* TO 'app_rt'@'{APP_HOST}'"))
        for g in grants_sql("cs23c02_test", f"'app_rt'@'{APP_HOST}'"):
            c.execute(text(g))
        c.execute(text("FLUSH PRIVILEGES"))
    yield


@pytest.fixture(autouse=True)
def clean():
    with MIG.begin() as c:
        c.execute(text("SET FOREIGN_KEY_CHECKS=0"))
        for t in Base.metadata.tables:
            c.execute(text(f"TRUNCATE TABLE `{t}`"))
        c.execute(text("SET FOREIGN_KEY_CHECKS=1"))
    mail.OUTBOX.clear()


@pytest.fixture
def client():
    with TestClient(app) as c:
        yield c


_n = iter(range(10**9))


def ip_headers(ip: str | None = None):
    return {"X-Forwarded-For": ip or f"10.{next(_n) % 250}.{next(_n) % 250}.{next(_n) % 250}"}


def last_code(email: str) -> str:
    for m in reversed(mail.OUTBOX):
        if m["to"] == email:
            found = re.search(r"code is (\d{6})", m["body"])
            if found:
                return found.group(1)
    raise AssertionError("no code emailed to " + email)


def make_user(email: str | None = None, pw: str = PW, verified: bool = True) -> User:
    email = email or f"u{next(_n)}@test.dev"
    with SessionLocal() as db:
        u = User(email=email, pw_hash=hash_password(pw), created_at=utcnow(), email_verified_at=utcnow() if verified else None)
        db.add(u)
        db.commit()
        return u


def login(client, email, pw=PW, device="phone", headers=None):
    return client.post("/v1/auth/login", json={"email": email, "password": pw, "device_label": device}, headers=headers or ip_headers())


def signed_in(client, email=None):
    u = make_user(email)
    r = login(client, u.email)
    assert r.status_code == 200, r.text
    return u, r.json(), {"Authorization": "Bearer " + r.json()["access_token"]}


def seed_codes(n=1, state="issued", edition="ed1", title="CS23C02 First Edition") -> list[str]:
    """Insert codes the way the CLI does and return the plaintext (which the database never holds)."""
    out = []
    with SessionLocal() as db:
        if not db.get(Edition, edition):
            db.add(Edition(id=edition, title=title, created_at=utcnow()))
            db.flush()
        b = ActivationBatch(edition_id=edition, label="test", created_at=utcnow())
        db.add(b)
        db.flush()
        for _ in range(n):
            c = codelib.generate()
            db.add(ActivationCode(batch_id=b.id, code_hmac=code_hmac(c), state=state))
            out.append(c)
        db.commit()
    return out
