from datetime import datetime, timezone

from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from .config import get_settings

_s = get_settings()
engine = create_engine(_s.database_url, pool_size=_s.db_pool_size, max_overflow=_s.db_max_overflow,
                       pool_pre_ping=True, pool_recycle=1800)


@event.listens_for(engine, "connect")
def _on_connect(conn, _):
    cur = conn.cursor()
    cur.execute("SET time_zone = '+00:00'")
    cur.execute("SET SESSION sql_mode = CONCAT(@@sql_mode, ',STRICT_ALL_TABLES')")
    cur.close()


SessionLocal = sessionmaker(bind=engine, expire_on_commit=False)


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def get_db():
    with SessionLocal() as db:
        yield db
