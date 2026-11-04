import secrets

from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError, OperationalError
from sqlalchemy.orm import Session

from .. import ratelimit
from ..audit import audit
from ..config import get_settings
from ..db import utcnow
from ..errors import ApiError
from ..models import ActivationBatch, ActivationCode, Edition, Entitlement
from ..security import hmac_hex
from . import codes

GENERIC = "That code can't be used. Check it and try again, or contact support."


def code_hmac(normalised: str) -> str:
    return hmac_hex(get_settings().code_pepper, "act|" + normalised)


def redeem(db: Session, user_id: str, raw: str, iph: str) -> dict:
    """The server is the only authority. Exactly one concurrent redemption of a code can win (the conditional UPDATE takes
    the row lock; losers see rowcount 0). If the account already owns the edition the whole transaction rolls back, so the
    code is NOT consumed."""
    ref = secrets.token_hex(4).upper()
    ratelimit.enforce(f"act-req:{user_id}", 30, 3600)
    if ratelimit.count(f"act-fail-user:{user_id}", 3600) >= 5 or ratelimit.count(f"act-fail-ip:{iph}", 86400) >= get_settings().activation_ip_fail_limit:
        audit("activation", "throttled", user_id, None, iph, ref)
        raise ratelimit.too_many(3600)
    code = codes.normalise(raw)
    if code is None or not codes.checksum_ok(code):  # typo: caught without touching the codes table
        audit("activation", "bad_format", user_id, None, iph, ref)
        raise ApiError(400, "That code doesn't look right. Check each character.", "bad_format", ref)
    h = code_hmac(code)

    for attempt in range(3):  # retry only on a MySQL deadlock/lock-wait timeout
        try:
            now = utcnow()
            res = db.execute(update(ActivationCode).where(ActivationCode.code_hmac == h, ActivationCode.state == "issued")
                             .values(state="redeemed", redeemed_by=user_id, redeemed_at=now))
            if res.rowcount != 1:
                db.rollback()
                state = db.scalar(select(ActivationCode.state).where(ActivationCode.code_hmac == h)) or "unknown"
                db.rollback()
                ratelimit.record(f"act-fail-user:{user_id}")
                ratelimit.record(f"act-fail-ip:{iph}")
                audit("activation", "fail_" + state, user_id, None, iph, ref)
                raise ApiError(400, GENERIC, "activation_failed", ref)
            row = db.execute(select(ActivationCode.id, ActivationCode.batch_id).where(ActivationCode.code_hmac == h)).one()
            edition_id = db.scalar(select(ActivationBatch.edition_id).where(ActivationBatch.id == row.batch_id))
            db.add(Entitlement(user_id=user_id, edition_id=edition_id, activation_code_id=row.id, created_at=now))
            db.flush()
            audit("activation", "success", user_id, f"code:{row.id}", iph, ref, db=db)
            db.commit()
            title = db.scalar(select(Edition.title).where(Edition.id == edition_id))
            return {"edition_id": edition_id, "title": title, "activated_at": now.isoformat() + "Z"}
        except IntegrityError:
            db.rollback()
            audit("activation", "already_entitled", user_id, None, iph, ref)
            raise ApiError(409, "This book is already activated on your account.", "already_activated", ref)
        except OperationalError as e:
            db.rollback()
            if attempt == 2 or getattr(e.orig, "args", [0])[0] not in (1213, 1205):
                raise
    raise ApiError(503, "Please try again.", "busy")
