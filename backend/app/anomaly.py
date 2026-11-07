"""Anomaly re-verification for credential sharing (ADR 0004, attack B1, K9).

Sharing is deterred and detected, never prevented, and an account is never locked. After each sign-in the account's
recent sessions are checked; if any signal fires, every session that has not confirmed since then (including ones created
during the following window) must enter a code emailed to the account's address before it can use the book's content
again. The owner confirms once per device; a friend can continue only if the owner forwards a fresh code each time.
Repeated triggers raise a support flag for a person to review. Signals use only the sessions table (no extra tracking)."""
from __future__ import annotations

from datetime import datetime, timedelta

from sqlalchemy import distinct, func, select
from sqlalchemy.orm import Session

from .audit import audit
from .config import get_settings
from .models import AuditLog, User, UserSession


def signals(db: Session, user_id: str, now: datetime) -> dict[str, int]:
    day, week = now - timedelta(hours=24), now - timedelta(days=7)
    q = select(UserSession).where(UserSession.user_id == user_id)
    signins = db.scalar(select(func.count()).select_from(q.where(UserSession.created_at >= day).subquery())) or 0
    ips = db.scalar(select(func.count(distinct(UserSession.ip_hash))).where(
        UserSession.user_id == user_id, UserSession.created_at >= day, UserSession.ip_hash.is_not(None))) or 0
    evictions = db.scalar(select(func.count()).select_from(q.where(UserSession.revoked_reason == "device_limit",
                                                                   UserSession.revoked_at >= week).subquery())) or 0
    return {"signins_24h": signins, "ips_24h": ips, "evictions_7d": evictions}


def fired(sig: dict[str, int]) -> list[str]:
    s = get_settings()
    limits = {"signins_24h": s.anomaly_signins_24h, "ips_24h": s.anomaly_ips_24h, "evictions_7d": s.anomaly_evictions_7d}
    return [k for k, lim in limits.items() if sig[k] >= lim]


def window_open(user: User, now: datetime) -> bool:
    return user.reverify_since is not None and now - user.reverify_since < timedelta(days=get_settings().reverify_window_days)


def needs_reverify(user: User, sess: UserSession) -> bool:
    """A session must confirm if a trigger happened after its last confirmation and the session existed then or was created
    while the window was open. Sessions created after the window closes are free again (until the next trigger)."""
    since = user.reverify_since
    if since is None or (sess.reverified_at is not None and sess.reverified_at >= since):
        return False
    return sess.created_at <= since + timedelta(days=get_settings().reverify_window_days)


def after_signin(db: Session, user: User, iph: str, now: datetime) -> None:
    """Called inside the sign-in transaction, after the new session is flushed. Starts a re-verification window when a
    signal fires and none is open; the caller commits."""
    if window_open(user, now):
        return
    why = fired(signals(db, user.id, now))
    if not why:
        return
    user.reverify_since = now
    audit("anomaly_detected", "reverify", user.id, ",".join(why)[:80], iph, db=db)
    s = get_settings()
    recent = db.scalar(select(func.count()).select_from(AuditLog).where(
        AuditLog.actor_id == user.id, AuditLog.action == "anomaly_detected", AuditLog.at >= now - timedelta(days=30))) or 0
    if recent + 1 >= s.support_flag_triggers and user.support_flag_at is None:
        user.support_flag_at = now
        audit("support_flag", "raised", user.id, f"triggers:{recent + 1}", iph, db=db)


def email_hint(email: str) -> str:
    """a***@gmail.com: enough for the owner to recognise, not enough to read off someone else's screen."""
    local, _, domain = email.partition("@")
    return (local[:1] + "***@" + domain) if domain else "your email address"
