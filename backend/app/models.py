"""Database schema. No application settings are imported here so Alembic can load it on its own."""
import uuid
from datetime import datetime

from sqlalchemy import BigInteger, Boolean, CHAR, CheckConstraint, ForeignKey, Index, Integer, String, UniqueConstraint
from sqlalchemy import JSON
from sqlalchemy.dialects.mysql import DATETIME, MEDIUMBLOB
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
    # Anomaly re-verification (ADR 0004, K9): while set, sessions not confirmed since this moment must enter an emailed code.
    reverify_since: Mapped[datetime | None] = mapped_column(DT)
    support_flag_at: Mapped[datetime | None] = mapped_column(DT)   # repeated anomalies: a person looks at the account


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
    ip_hash: Mapped[str | None] = mapped_column(CHAR(16))          # keyed hash of the sign-in address (never the address)
    reverified_at: Mapped[datetime | None] = mapped_column(DT)
    __table_args__ = (Index("ix_sessions_user_active", "user_id", "revoked_at"), Index("ix_sessions_user_created", "user_id", "created_at"))


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


class ContentRelease(Base):
    """One imported, signed App Content Package for an edition. Only 'published' (and 'superseded', for in-flight downloads) is served."""
    __tablename__ = "content_releases"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    version: Mapped[str] = mapped_column(String(32))
    book_id: Mapped[str] = mapped_column(String(16))
    manifest: Mapped[bytes] = mapped_column(MEDIUMBLOB)  # the exact signed bytes
    manifest_sha256: Mapped[str] = mapped_column(CHAR(64))
    signature: Mapped[str] = mapped_column(String(120))
    min_app_version: Mapped[str] = mapped_column(String(32))
    status: Mapped[str] = mapped_column(String(12), default="draft")
    created_at: Mapped[datetime] = mapped_column(DT)
    published_at: Mapped[datetime | None] = mapped_column(DT)
    __table_args__ = (UniqueConstraint("edition_id", "version", name="uq_release_edition_version"),
                      CheckConstraint("status IN ('draft','published','superseded')", name="ck_release_status"),
                      Index("ix_release_edition_status", "edition_id", "status"))


class ContentFile(Base):
    __tablename__ = "content_files"
    release_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("content_releases.id", ondelete="CASCADE"), primary_key=True)
    path: Mapped[str] = mapped_column(String(200), primary_key=True)
    sha256: Mapped[str] = mapped_column(CHAR(64), index=True)
    size: Mapped[int] = mapped_column(Integer)


class ContentLesson(Base):
    """Lessons of a release in reading order; used to validate progress events and compute where to resume."""
    __tablename__ = "content_lessons"
    release_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("content_releases.id", ondelete="CASCADE"), primary_key=True)
    lesson_id: Mapped[str] = mapped_column(String(16), primary_key=True)
    ord: Mapped[int] = mapped_column(Integer)
    title: Mapped[str] = mapped_column(String(200))
    unit_id: Mapped[str] = mapped_column(String(16))
    topic_id: Mapped[str] = mapped_column(String(16))


class ProtectedContent(Base):
    """Answers, explanations, hidden tests, hints, solutions. Never served by Phase 3 endpoints; used by Phases 4-5."""
    __tablename__ = "protected_content"
    release_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("content_releases.id", ondelete="CASCADE"), primary_key=True)
    kind: Mapped[str] = mapped_column(String(12), primary_key=True)  # item | exercise
    content_id: Mapped[str] = mapped_column(String(16), primary_key=True)
    data: Mapped[dict] = mapped_column(JSON)


class ProgressEvent(Base):
    __tablename__ = "progress_events"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(CHAR(36), ForeignKey("users.id"))
    event_uuid: Mapped[str] = mapped_column(CHAR(36))  # client-generated; makes retries idempotent
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    kind: Mapped[str] = mapped_column(String(24))
    content_id: Mapped[str] = mapped_column(String(16))
    client_ts: Mapped[datetime | None] = mapped_column(DT)  # untrusted, informational
    server_ts: Mapped[datetime] = mapped_column(DT)
    __table_args__ = (UniqueConstraint("user_id", "event_uuid", name="uq_progress_user_event"),
                      Index("ix_progress_user_edition_kind", "user_id", "edition_id", "kind"))


class ExerciseAttempt(Base):
    """One graded Check of an exercise (server-verified, ADR 0009). Drives the hint/solution policy and exercise completion."""
    __tablename__ = "exercise_attempts"
    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    user_id: Mapped[str] = mapped_column(CHAR(36), ForeignKey("users.id"))
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    release_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("content_releases.id"))
    exercise_id: Mapped[str] = mapped_column(String(16))
    passed: Mapped[bool] = mapped_column(Boolean)
    tests_passed: Mapped[int] = mapped_column(Integer)
    tests_total: Mapped[int] = mapped_column(Integer)
    code_sha256: Mapped[str] = mapped_column(CHAR(64))
    created_at: Mapped[datetime] = mapped_column(DATETIME(fsp=6))
    __table_args__ = (Index("ix_attempts_user_exercise", "user_id", "edition_id", "exercise_id"),)


class AssessmentAttempt(Base):
    """One attempt at a unit assessment (ADR 0009). The server owns the state: what was shown (layout), what was saved
    (answers + a monotonic seq), and, after submit, the score and a snapshot of the results as they were shown."""
    __tablename__ = "assessment_attempts"
    id: Mapped[str] = mapped_column(CHAR(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    user_id: Mapped[str] = mapped_column(CHAR(36), ForeignKey("users.id"))
    edition_id: Mapped[str] = mapped_column(String(40), ForeignKey("editions.id"))
    release_id: Mapped[int] = mapped_column(BigInteger, ForeignKey("content_releases.id"))
    assessment_id: Mapped[str] = mapped_column(String(16))
    status: Mapped[str] = mapped_column(String(12))  # in_progress | submitted
    seed: Mapped[int] = mapped_column(BigInteger)
    layout: Mapped[list] = mapped_column(JSON)       # [{"item_id", "options": [ids in shown order]}]
    answers: Mapped[dict] = mapped_column(JSON)      # {item_id: option_id}
    seq: Mapped[int] = mapped_column(Integer, default=0)
    started_at: Mapped[datetime] = mapped_column(DATETIME(fsp=6))
    expires_at: Mapped[datetime] = mapped_column(DATETIME(fsp=6))
    submitted_at: Mapped[datetime | None] = mapped_column(DATETIME(fsp=6), nullable=True)
    submit_request_id: Mapped[str | None] = mapped_column(CHAR(36), nullable=True)
    auto_submitted: Mapped[bool] = mapped_column(Boolean, default=False)
    score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    max_score: Mapped[int | None] = mapped_column(Integer, nullable=True)
    result: Mapped[list | None] = mapped_column(JSON, nullable=True)
    __table_args__ = (CheckConstraint("status IN ('in_progress','submitted')", name="ck_attempt_status"),
                      Index("ix_asm_attempts_user", "user_id", "edition_id", "assessment_id"))
