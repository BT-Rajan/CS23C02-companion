from datetime import datetime, timedelta
from typing import Literal

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import ratelimit
from .db import get_db, utcnow
from .deps import Principal, get_current
from .entitlements import entitled_edition
from .errors import ApiError
from .models import ContentLesson, ExerciseAttempt, ProgressEvent
from .content.importer import current_release

router = APIRouter(prefix="/v1/progress", tags=["progress"])


class EventIn(BaseModel):
    model_config = ConfigDict(extra="forbid")  # no user_id, no score, nothing the server derives itself
    id: str = Field(pattern=r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    kind: Literal["lesson_opened", "lesson_completed"]  # assessment/exercise outcomes are server-derived in later phases
    content_id: str = Field(pattern=r"^LSN-\d{4,6}$")
    client_ts: datetime | None = None


class BatchIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    edition: str | None = Field(default=None, max_length=40)
    events: list[EventIn] = Field(min_length=1, max_length=100)


def _release_or_404(db, edition_id):
    rel = current_release(db, edition_id)
    if rel is None:
        raise ApiError(404, "Content isn't available yet.", "no_content")
    return rel


@router.put("/events")
def put_events(body: BatchIn, p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    """Idempotent: replaying a batch is safe. The user always comes from the token. Unknown lesson ids are reported per event."""
    ratelimit.enforce(f"progress:{p.user.id}", 600, 3600)
    ed = entitled_edition(db, p.user.id, body.edition)
    rel = _release_or_404(db, ed)
    valid = set(db.scalars(select(ContentLesson.lesson_id).where(ContentLesson.release_id == rel.id)))
    now, out = utcnow(), []
    for e in body.events:
        if e.content_id not in valid:
            out.append({"id": e.id, "status": "unknown_content"})
            continue
        cts = e.client_ts.replace(tzinfo=None) if e.client_ts and e.client_ts.tzinfo is None else (e.client_ts.astimezone().replace(tzinfo=None) if e.client_ts else None)
        try:
            with db.begin_nested():
                db.add(ProgressEvent(user_id=p.user.id, event_uuid=e.id.lower(), edition_id=ed, kind=e.kind, content_id=e.content_id,
                                     client_ts=cts if cts and abs(cts - now) < timedelta(days=366) else None, server_ts=now))
                db.flush()
            out.append({"id": e.id, "status": "accepted"})
        except IntegrityError:
            out.append({"id": e.id, "status": "duplicate"})
    db.commit()
    return {"results": out}


@router.get("")
def get_progress(edition: str | None = Query(default=None, max_length=40), p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    ed = entitled_edition(db, p.user.id, edition)
    rel = _release_or_404(db, ed)
    lessons = db.scalars(select(ContentLesson).where(ContentLesson.release_id == rel.id).order_by(ContentLesson.ord)).all()
    by_id = {l.lesson_id: l for l in lessons}
    events = db.execute(select(ProgressEvent.kind, ProgressEvent.content_id).where(ProgressEvent.user_id == p.user.id, ProgressEvent.edition_id == ed)
                        .order_by(ProgressEvent.server_ts.desc(), ProgressEvent.id.desc())).all()
    completed = {c for k, c in events if k == "lesson_completed" and c in by_id}
    opened = next((c for k, c in events if k == "lesson_opened" and c in by_id and c not in completed), None)
    resume, reason = None, None
    if opened:
        resume, reason = by_id[opened], "in_progress"
    else:
        top = max((by_id[c].ord for c in completed), default=0)
        nxt = next((l for l in lessons if l.lesson_id not in completed and l.ord > top), None) or next((l for l in lessons if l.lesson_id not in completed), None)
        if nxt:
            resume, reason = nxt, ("next" if completed else "start")
    exercises_passed = sorted(set(db.scalars(select(ExerciseAttempt.exercise_id).where(
        ExerciseAttempt.user_id == p.user.id, ExerciseAttempt.edition_id == ed, ExerciseAttempt.passed.is_(True)))))
    from .assessments import summary as assessment_summary
    return {"edition_id": ed, "content_version": rel.version, "total_lessons": len(lessons), "exercises_passed": exercises_passed,
            "assessments": assessment_summary(db, p.user.id, ed),
            "completed": [l.lesson_id for l in lessons if l.lesson_id in completed], "finished": resume is None,
            "resume": None if resume is None else {"lesson_id": resume.lesson_id, "title": resume.title, "unit_id": resume.unit_id,
                                                   "topic_id": resume.topic_id, "reason": reason}}
