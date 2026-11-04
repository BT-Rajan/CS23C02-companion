"""Small database-backed limiter (single deployment). Events are written in their own short transaction so a rolled-back
request still counts. `enforce` is check-then-record, so under heavy concurrency the limit can be exceeded slightly."""
from datetime import timedelta

from sqlalchemy import delete, func, select

from .db import SessionLocal, utcnow
from .errors import ApiError
from .models import RateEvent


def count(key: str, window_s: int) -> int:
    with SessionLocal() as s:
        return s.scalar(select(func.count()).select_from(RateEvent).where(
            RateEvent.key == key, RateEvent.at >= utcnow() - timedelta(seconds=window_s))) or 0


def record(key: str) -> None:
    with SessionLocal() as s:
        s.add(RateEvent(key=key, at=utcnow()))
        s.commit()


def purge(older_than_days: int = 2) -> int:
    """Housekeeping for a scheduled job (python -m app.cli purge-rate-events). Never run it inside a request: a bulk DELETE
    can deadlock with concurrent inserts."""
    with SessionLocal() as s:
        n = s.execute(delete(RateEvent).where(RateEvent.at < utcnow() - timedelta(days=older_than_days))).rowcount
        s.commit()
        return n


def too_many(retry_after: int = 60) -> ApiError:
    return ApiError(429, "Too many attempts. Please wait a while and try again.", "rate_limited",
                    headers={"Retry-After": str(retry_after)})


def enforce(key: str, limit: int, window_s: int) -> None:
    if count(key, window_s) >= limit:
        raise too_many(min(window_s, 900))
    record(key)
