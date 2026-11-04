"""Database schema. No application settings are imported here so Alembic can load it on its own."""
import uuid
from datetime import datetime

from sqlalchemy import BigInteger, CHAR, CheckConstraint, ForeignKey, Index, Integer, String, UniqueConstraint
from sqlalchemy.dialects.mysql import DATETIME
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


def _uuid() -> str:
    return str(uuid.uuid4())


DT = DATETIME(fsp=6)  # microsecond precision, always UTC naive (the connection sets time_zone='+00:00')


class User(Base):
    __tablename__ = "users"
    id: Mapped[str] = mapped_column(CHAR(36), primary_key=True, default=_uuid)
    email: Mapped[str] = mapped_column(String(254), unique=True)  # stored lower-cased
    pw_hash: Mapped[str] = mapped_column(String(255))
    email_verified_at: Mapped[datetime | None] = mapped_column(DT)
    created_at: Mapped[datetime] = mapped_column(DT)
    disabled_at: Mapped[datetime | None] = mapped_column(DT)


class UserSession(Base):
    """One signed-in device. The refresh token is stored only as a SHA-256 hash and rotates on every use."""
    __tablename__ = "sessions"
    id: Mapped[str] = mapped_column(CHAR(36), primary_key=True, default=_uuid)
    user_id: Mapped[str] = mapped_column(CHAR(36), ForeignKey("users.id", ondelete="CASCADE"))
    refresh_hash: Mapped[str] = mapped_column(CHAR(64), unique=True)
    prev_refresh_hash: Mapped[str | None] = mapped_column(CHAR(64), index=True)
    rotated_at: Mapped[datetime | None] = mapped_column(DT)
    device_label: Mapped[str] = mapped_column(String(80), default="")
    created_at: Mapped[datetime] = mapped_column(DT)
    last_used_at: Mapped[datetime] = mapped_column(DT)
    revoked_at: Mapped[datetime | None] = mapped_column(DT)
    revoked_reason: Mapped[str | None] = mapped_column(String(32))
    __table_args__ = (Index("ix_sessions_user_active", "user_id", "revoked_at"),)


class OneTimeCode(Base):
    __tablename__ = "one_time_codes"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    email: Mapped[str] = mapped_column(String(254))
    purpose: Mapped[str] = mapped_column(String(16))  # verify | reset
    code_hash: Mapped[str] = mapped_column(CHAR(64))
    expires_at: Mapped[datetime] = mapped_column(DT)
    attempts: Mapped[int] = mapped_column(Integer, default=0)
    used_at: Mapped[datetime | None] = mapped_column(DT)
    created_at: Mapped[datetime] = mapped_column(DT)
    __table_args__ = (Index("ix_otc_email_purpose", "email", "purpose"),)


class RateEvent(Base):
    __tablename__ = "rate_events"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    key: Mapped[str] = mapped_column(String(120))
    at: Mapped[datetime] = mapped_column(DT)
    __table_args__ = (Index("ix_rate_key_at", "key", "at"),)


class Edition(Base):
    __tablename__ = "editions"
    id: Mapped[str] = mapped_column(String(40), primary_key=True)
    title: Mapped[str] = mapped_column(String(200))
    created_at: Mapped[datetime] = mapped_column(DT)


class ActivationBatch(Base):
    __tablename__ = "activation_batches"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    label: Mapped[str] = mapped_column(String(120))
    created_at: Mapped[datetime] = mapped_column(DT)


class ActivationCode(Base):
    __tablename__ = "activation_codes"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    batch_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("activation_batches.id"))
    code_hmac: Mapped[str] = mapped_column(CHAR(64), unique=True)  # HMAC-SHA256(pepper, normalised code); never the code
    state: Mapped[str] = mapped_column(String(12), default="unissued")
    redeemed_by: Mapped[str | None] = mapped_column(CHAR(36), ForeignKey("users.id"))
    redeemed_at: Mapped[datetime | None] = mapped_column(DT)
    __table_args__ = (
        CheckConstraint("state IN ('unissued','issued','redeemed','revoked')", name="ck_activation_state"),
        Index("ix_activation_batch_state", "batch_id", "state"),
    )


class Entitlement(Base):
    __tablename__ = "entitlements"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(CHAR(36), ForeignKey("users.id"))
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    activation_code_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("activation_codes.id"), unique=True)
    created_at: Mapped[datetime] = mapped_column(DT)
    revoked_at: Mapped[datetime | None] = mapped_column(DT)
    __table_args__ = (UniqueConstraint("user_id", "edition_id", name="uq_entitlement_user_edition"),)


class AuditLog(Base):
    """Append-only: the runtime MySQL user has INSERT and SELECT only (docs/ops/grant_runtime.sql)."""
    __tablename__ = "audit_log"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(DT)
    actor_id: Mapped[str | None] = mapped_column(CHAR(36))
    action: Mapped[str] = mapped_column(String(48))
    object: Mapped[str | None] = mapped_column(String(80))
    outcome: Mapped[str] = mapped_column(String(24))
    ip_hash: Mapped[str | None] = mapped_column(CHAR(16))
    ref: Mapped[str | None] = mapped_column(CHAR(8))
    __table_args__ = (Index("ix_audit_action_at", "action", "at"), Index("ix_audit_actor", "actor_id", "at"))
