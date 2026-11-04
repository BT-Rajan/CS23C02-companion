from sqlalchemy.orm import Session

from .db import SessionLocal, utcnow
from .models import AuditLog


def audit(action: str, outcome: str, actor_id: str | None = None, obj: str | None = None, ip_hash: str | None = None,
          ref: str | None = None, db: Session | None = None) -> None:
    """With `db`, the row joins the caller's transaction (atomic with the action); otherwise it is written on its own."""
    row = AuditLog(at=utcnow(), actor_id=actor_id, action=action, object=obj, outcome=outcome, ip_hash=ip_hash, ref=ref)
    if db is not None:
        db.add(row)
        return
    with SessionLocal() as s:
        s.add(row)
        s.commit()
