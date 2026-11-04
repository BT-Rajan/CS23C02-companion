import hmac
import secrets
from datetime import timedelta

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .. import ratelimit
from ..audit import audit
from ..config import get_settings
from ..db import utcnow
from ..models import OneTimeCode, User, UserSession
from ..security import hmac_hex, make_access_token, new_refresh_token, sha256_hex


# ---- one-time codes (email verification, password reset) -------------------------------------------------------------
def _code_hash(email: str, purpose: str, code: str) -> str:
    return hmac_hex(get_settings().aux_pepper, f"otp|{purpose}|{email}|{code}")


def may_send(email: str, purpose: str) -> bool:
    """60 s cooldown and 5 sends a day per email and purpose. Silent: callers answer identically either way."""
    key = f"otp-send:{purpose}:{sha256_hex(email)[:32]}"
    if ratelimit.count(key, 60) >= 1 or ratelimit.count(key, 86400) >= 5:
        return False
    ratelimit.record(key)
    return True


def issue_code(db: Session, email: str, purpose: str) -> str:
    now = utcnow()
    db.execute(update(OneTimeCode).where(OneTimeCode.email == email, OneTimeCode.purpose == purpose,
                                         OneTimeCode.used_at.is_(None)).values(used_at=now))
    code = f"{secrets.randbelow(10**6):06d}"
    db.add(OneTimeCode(email=email, purpose=purpose, code_hash=_code_hash(email, purpose, code), created_at=now,
                       expires_at=now + timedelta(seconds=get_settings().otp_ttl_seconds)))
    db.commit()
    return code


def verify_code(db: Session, email: str, purpose: str, code: str) -> bool:
    """Attempts are counted with an atomic UPDATE before comparing, and success consumes the code with another atomic
    UPDATE, so parallel guesses cannot exceed the attempt limit and a code cannot be used twice."""
    s = get_settings()
    now = utcnow()
    row = db.scalar(select(OneTimeCode).where(OneTimeCode.email == email, OneTimeCode.purpose == purpose,
                                              OneTimeCode.used_at.is_(None), OneTimeCode.expires_at > now)
                    .order_by(OneTimeCode.id.desc()).limit(1))
    if row is None:
        hmac.compare_digest(_code_hash(email, purpose, code), "0" * 64)
        return False
    counted = db.execute(update(OneTimeCode).where(OneTimeCode.id == row.id, OneTimeCode.used_at.is_(None),
                                                   OneTimeCode.attempts < s.otp_max_attempts)
                         .values(attempts=OneTimeCode.attempts + 1))
    db.commit()
    if counted.rowcount != 1:
        return False
    if not hmac.compare_digest(_code_hash(email, purpose, code), row.code_hash):
        return False
    used = db.execute(update(OneTimeCode).where(OneTimeCode.id == row.id, OneTimeCode.used_at.is_(None)).values(used_at=now))
    db.commit()
    return used.rowcount == 1


# ---- sessions --------------------------------------------------------------------------------------------------------
def _active(db: Session, user_id: str):
    cutoff = utcnow() - timedelta(days=get_settings().refresh_idle_days)
    return db.scalars(select(UserSession).where(UserSession.user_id == user_id, UserSession.revoked_at.is_(None),
                                                UserSession.last_used_at > cutoff).order_by(UserSession.last_used_at)).all()


def revoke(db: Session, sess: UserSession, reason: str) -> None:
    if sess.revoked_at is None:
        sess.revoked_at, sess.revoked_reason = utcnow(), reason


def revoke_all(db: Session, user_id: str, reason: str) -> None:
    for s in _active(db, user_id):
        revoke(db, s, reason)


def tokens_for(sess: UserSession, refresh: str) -> dict:
    now = utcnow()
    return {"access_token": make_access_token(sess.user_id, sess.id, _epoch(now)), "refresh_token": refresh, "token_type": "Bearer", "expires_in": get_settings().access_ttl_seconds}


def _epoch(naive_utc) -> int:
    from datetime import timezone
    return int(naive_utc.replace(tzinfo=timezone.utc).timestamp())


def create_session(db: Session, user: User, device_label: str, iph: str) -> dict:
    now = utcnow()
    refresh = new_refresh_token()
    sess = UserSession(user_id=user.id, refresh_hash=sha256_hex(refresh), device_label=(device_label or "")[:80],
                       created_at=now, last_used_at=now)
    db.add(sess)
    db.flush()
    active = [s for s in _active(db, user.id) if s.id != sess.id]
    over = len(active) + 1 - get_settings().max_sessions
    for old in active[:max(over, 0)]:  # oldest first: a replacement phone never blocks sign-in
        revoke(db, old, "device_limit")
        audit("session_revoked", "device_limit", user.id, f"session:{old.id}", iph, db=db)
    audit("session_created", "success", user.id, f"session:{sess.id}", iph, db=db)
    db.commit()
    return tokens_for(sess, refresh)


def rotate(db: Session, refresh_token: str, iph: str) -> dict | None:
    """Rotating refresh tokens with reuse detection. A token replayed after its grace period revokes the session."""
    s = get_settings()
    h, now = sha256_hex(refresh_token), utcnow()
    idle_cutoff = now - timedelta(days=s.refresh_idle_days)
    sess = db.scalar(select(UserSession).where(UserSession.refresh_hash == h).with_for_update())
    if sess is None:
        sess = db.scalar(select(UserSession).where(UserSession.prev_refresh_hash == h).with_for_update())
        if sess is None:
            return None
        recent = sess.rotated_at is not None and now - sess.rotated_at <= timedelta(seconds=s.refresh_grace_seconds)
        if sess.revoked_at is None and not recent:  # an old token came back: assume theft
            revoke(db, sess, "refresh_reuse")
            audit("refresh_reuse", "revoked", sess.user_id, f"session:{sess.id}", iph, db=db)
            db.commit()
            return None
    if sess.revoked_at is not None or sess.last_used_at < idle_cutoff:
        db.rollback()
        return None
    user = db.get(User, sess.user_id)
    if not user or user.disabled_at is not None:
        db.rollback()
        return None
    new = new_refresh_token()
    sess.prev_refresh_hash, sess.refresh_hash, sess.rotated_at, sess.last_used_at = sess.refresh_hash, sha256_hex(new), now, now
    db.commit()
    return tokens_for(sess, new)
