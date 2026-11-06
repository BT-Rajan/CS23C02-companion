from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.orm import Session

from .db import get_db
from . import anomaly
from .deps import Principal, get_current_any
from .models import Edition, Entitlement

router = APIRouter(prefix="/v1", tags=["me"])


@router.get("/me")
def me(p: Principal = Depends(get_current_any), db: Session = Depends(get_db)):
    rows = db.execute(select(Entitlement, Edition).join(Edition, Edition.id == Entitlement.edition_id)
                      .where(Entitlement.user_id == p.user.id, Entitlement.revoked_at.is_(None))).all()
    return {"email": p.user.email, "reverify_required": anomaly.needs_reverify(p.user, p.session),
            "entitlements": [{"edition_id": e.id, "title": e.title, "activated_at": en.created_at.isoformat() + "Z"} for en, e in rows]}
