"""Minimal administration: editions and activation-code batches. Plaintext codes exist only in the output file you request
(written 0600). Hand that file to the printer through an encrypted channel and delete it after print confirmation."""
import argparse
import csv
import os
import sys

from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError

import pathlib

from . import mail, ratelimit
from .content import importer
from .activation import codes
from .activation.service import code_hmac
from .audit import audit
from .db import SessionLocal, utcnow
from .models import ActivationBatch, ActivationCode, AuditLog, Base, Edition, User


def grants_sql(schema: str, user: str) -> list[str]:
    """Per-table runtime grants. (MySQL cannot REVOKE a table privilege that was granted at database level, so we grant per
    table.) audit_log is append-only: SELECT and INSERT only. alembic_version is not visible to the runtime user."""
    out = []
    for name in sorted(Base.metadata.tables):
        privs = "SELECT, INSERT" if name == "audit_log" else "SELECT, INSERT, UPDATE, DELETE"
        out.append(f"GRANT {privs} ON `{schema}`.`{name}` TO {user};")
    return out


def print_grants(a):
    print("\n".join(grants_sql(a.schema, a.user)))


def create_edition(a):
    with SessionLocal() as db:
        db.add(Edition(id=a.id, title=a.title, created_at=utcnow()))
        db.commit()
    print("edition created:", a.id)


def create_batch(a):
    if a.count < 1 or a.count > 100000:
        sys.exit("count must be 1..100000")
    fd = os.open(a.out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with SessionLocal() as db, os.fdopen(fd, "w", newline="") as f:
        if not db.get(Edition, a.edition):
            sys.exit("unknown edition")
        batch = ActivationBatch(edition_id=a.edition, label=a.label, created_at=utcnow())
        db.add(batch)
        db.flush()
        w = csv.writer(f)
        w.writerow(["serial", "code"])
        made = 0
        while made < a.count:
            c = codes.generate()
            try:
                with db.begin_nested():
                    db.add(ActivationCode(batch_id=batch.id, code_hmac=code_hmac(c), state="unissued"))
                    db.flush()
            except IntegrityError:
                continue  # astronomically unlikely collision
            made += 1
            w.writerow([f"{batch.id}-{made:06d}", codes.pretty(c)])
        audit("batch_created", "success", None, f"batch:{batch.id}", db=db)
        db.commit()
    print(f"batch {batch.id}: {a.count} codes written to {a.out} (state: unissued). Protect and delete that file after printing.")


def _set_state(a, frm, to):
    with SessionLocal() as db:
        n = db.execute(update(ActivationCode).where(ActivationCode.batch_id == a.batch, ActivationCode.state.in_(frm)).values(state=to)).rowcount
        audit(f"batch_{to}", "success", None, f"batch:{a.batch}", db=db)
        db.commit()
    print(f"{n} codes -> {to}")


def report(a):
    with SessionLocal() as db:
        for state, n in db.execute(select(ActivationCode.state, func.count()).where(ActivationCode.batch_id == a.batch).group_by(ActivationCode.state)):
            print(f"{state:10} {n}")


def import_content(a):
    with SessionLocal() as db:
        try:
            rid = importer.import_release(db, a.edition, pathlib.Path(a.public), pathlib.Path(a.protected), publish=a.publish)
        except importer.PackageError as e:
            sys.exit(f"import refused: {e}")
    print(f"release {rid} imported ({'published' if a.publish else 'draft'})")


def publish_release(a):
    with SessionLocal() as db:
        importer.publish_release(db, a.release)
    print("published")


def flagged_accounts(a):
    """Accounts with repeated anomaly triggers (ADR 0004). Review: contact the owner; never lock automatically."""
    with SessionLocal() as db:
        rows = db.scalars(select(User).where(User.support_flag_at.is_not(None)).order_by(User.support_flag_at)).all()
        for u in rows:
            n = db.scalar(select(func.count()).select_from(AuditLog).where(AuditLog.actor_id == u.id, AuditLog.action == "anomaly_detected")) or 0
            print(f"{u.support_flag_at:%Y-%m-%d %H:%M}  {u.email}  triggers:{n}  reverify_since:{u.reverify_since or '-'}")
        print(f"{len(rows)} flagged")


def clear_flag(a):
    with SessionLocal() as db:
        u = db.scalar(select(User).where(User.email == a.email.lower()))
        if u is None:
            sys.exit("no such account")
        u.support_flag_at = None
        audit("support_flag", "cleared", u.id, a.note[:80], db=db)
        db.commit()
    print("flag cleared")


def send_test_email(a):
    mail.send_email(a.to, "CS23C02 Companion test", "If you can read this, SMTP is configured.")
    print("sent (check the inbox; failures are logged without secrets)")


def main():
    p = argparse.ArgumentParser(prog="python -m app.cli")
    sub = p.add_subparsers(required=True)
    x = sub.add_parser("create-edition"); x.add_argument("--id", required=True); x.add_argument("--title", required=True); x.set_defaults(f=create_edition)
    x = sub.add_parser("create-batch"); x.add_argument("--edition", required=True); x.add_argument("--count", type=int, required=True)
    x.add_argument("--label", required=True); x.add_argument("--out", required=True); x.set_defaults(f=create_batch)
    x = sub.add_parser("release-batch"); x.add_argument("--batch", type=int, required=True); x.set_defaults(f=lambda a: _set_state(a, ["unissued"], "issued"))
    x = sub.add_parser("revoke-batch"); x.add_argument("--batch", type=int, required=True); x.set_defaults(f=lambda a: _set_state(a, ["unissued", "issued"], "revoked"))
    x = sub.add_parser("batch-report"); x.add_argument("--batch", type=int, required=True); x.set_defaults(f=report)
    x = sub.add_parser("print-grants"); x.add_argument("--schema", required=True); x.add_argument("--user", required=True, help="e.g. \"'cs23c02_app'@'%%'\""); x.set_defaults(f=print_grants)
    x = sub.add_parser("purge-rate-events"); x.set_defaults(f=lambda a: print(ratelimit.purge(), "rate events purged"))
    x = sub.add_parser("import-content"); x.add_argument("--edition", required=True); x.add_argument("--public", required=True)
    x.add_argument("--protected", required=True); x.add_argument("--publish", action="store_true"); x.set_defaults(f=import_content)
    x = sub.add_parser("publish-release"); x.add_argument("--release", type=int, required=True); x.set_defaults(f=publish_release)
    x = sub.add_parser("flagged-accounts"); x.set_defaults(f=flagged_accounts)
    x = sub.add_parser("clear-flag"); x.add_argument("--email", required=True); x.add_argument("--note", required=True); x.set_defaults(f=clear_flag)
    x = sub.add_parser("send-test-email"); x.add_argument("to"); x.set_defaults(f=send_test_email)
    a = p.parse_args()
    a.f(a)


if __name__ == "__main__":
    main()
