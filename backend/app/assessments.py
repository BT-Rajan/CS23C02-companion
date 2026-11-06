"""Unit assessments: a server-owned state machine (ADR 0009; approved policy, open question #2).

  POST /v1/assessments/{id}/attempts           start, or resume the open attempt
  GET  /v1/assessments/{id}/attempts           this student's history and when the next attempt is allowed
  GET  /v1/assessments/attempts/{attempt}      the open attempt (questions + saved answers) or, once submitted, the review
  PUT  /v1/assessments/attempts/{attempt}/answers   autosave: the whole answer map with a sequence number that must increase
  POST /v1/assessments/attempts/{attempt}/submit    idempotent; the server scores and stores a snapshot of the results

Integrity: questions are chosen and ordered by the server, options shuffled per attempt; nothing sent before submit says
which option is right; the client never sends a score; attempt ids are random and only ever visible to their owner.
Policy: one attempt per assessment per 24 hours after the previous one; an attempt expires 3 hours after it starts (saved
answers are then scored automatically); results and explanations are shown after submit and can be reviewed later.
"""
import json
import pathlib
import random
import secrets
from datetime import timedelta

from fastapi import APIRouter, Depends, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import ratelimit
from .config import get_settings
from .content.importer import current_release
from .db import get_db, utcnow
from .deps import Principal, get_current
from .entitlements import entitled_edition
from .errors import ApiError
from .models import AssessmentAttempt, ContentFile, ProtectedContent, User

router = APIRouter(prefix="/v1/assessments", tags=["assessments"])
SESSION = timedelta(hours=3)
COOLDOWN = timedelta(hours=24)
GRACE = timedelta(minutes=5)
_UUID = r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
_NOT_FOUND = ("This assessment isn't available.", "not_found")


def _iso(d):
    return None if d is None else d.isoformat() + "Z"


def _public(db: Session, release_id: int, path: str) -> dict | None:
    sha = db.scalar(select(ContentFile.sha256).where(ContentFile.release_id == release_id, ContentFile.path == path))
    f = pathlib.Path(get_settings().content_dir) / "files" / (sha or "missing")
    return json.loads(f.read_text()) if sha and f.is_file() else None


def _assessment(db: Session, uid: str, edition: str | None, asm_id: str):
    ed = entitled_edition(db, uid, edition)
    rel = current_release(db, ed)
    if rel is None:
        raise ApiError(404, "Content isn't available yet.", "no_content")
    a = _public(db, rel.id, f"assessments/{asm_id}.json")
    if a is None or a.get("purpose") != "unit_assessment":
        raise ApiError(404, *_NOT_FOUND)
    return ed, rel, a


def _own_attempt(db: Session, uid: str, attempt_id: str, lock: bool = False) -> AssessmentAttempt:
    q = select(AssessmentAttempt).where(AssessmentAttempt.id == attempt_id, AssessmentAttempt.user_id == uid)
    att = db.scalar(q.with_for_update() if lock else q)
    if att is None:  # someone else's attempt looks exactly like a missing one (attack C2)
        raise ApiError(404, "Attempt not found.", "not_found")
    return att


def _questions(db: Session, att: AssessmentAttempt) -> list[dict]:
    out = []
    for slot in att.layout:
        it = _public(db, att.release_id, f"items/{slot['item_id']}.json") or {}
        text_by_id = {o["id"]: o["text"] for o in it.get("options", [])}
        q = {"item_id": slot["item_id"], "type": it.get("type", "mcq"), "stem": it.get("stem", ""),
             "options": [{"id": o, "text": text_by_id.get(o, "")} for o in slot["options"]]}
        if it.get("code"):
            q["code"] = it["code"]
        out.append(q)
    return out


def _finalize(db: Session, att: AssessmentAttempt, auto: bool, request_id: str | None = None) -> None:
    """Scores from the stored answers only, and keeps a snapshot of exactly what the student saw and chose."""
    keys = {r.content_id: r.data for r in db.scalars(select(ProtectedContent).where(
        ProtectedContent.release_id == att.release_id, ProtectedContent.kind == "item",
        ProtectedContent.content_id.in_([s["item_id"] for s in att.layout])))}
    result, score = [], 0
    for q in _questions(db, att):
        k = keys.get(q["item_id"], {})
        chosen = att.answers.get(q["item_id"])
        right = chosen is not None and chosen == k.get("answer")
        score += right
        result.append({**q, "chosen": chosen, "answer": k.get("answer"), "correct": right, "explanation": k.get("explanation", ""),
                       "why": None if right or chosen is None else (k.get("option_explanations") or {}).get(chosen)})
    att.status, att.submitted_at, att.auto_submitted = "submitted", utcnow(), auto
    att.score, att.max_score, att.result, att.submit_request_id = score, len(att.layout), result, request_id


def _expire_if_due(db: Session, att: AssessmentAttempt) -> None:
    if att.status == "in_progress" and utcnow() > att.expires_at + GRACE:
        _finalize(db, att, auto=True)
        db.commit()


def _view(att: AssessmentAttempt, db: Session, title: str = "") -> dict:
    base = {"attempt_id": att.id, "assessment_id": att.assessment_id, "title": title, "status": att.status,
            "started_at": _iso(att.started_at), "expires_at": _iso(att.expires_at)}
    if att.status == "in_progress":
        return {**base, "seq": att.seq, "answers": att.answers, "questions": _questions(db, att)}
    return {**base, "submitted_at": _iso(att.submitted_at), "auto_submitted": att.auto_submitted, "score": att.score,
            "max_score": att.max_score, "review": att.result}


def _title(db: Session, att: AssessmentAttempt) -> str:
    return (_public(db, att.release_id, f"assessments/{att.assessment_id}.json") or {}).get("title", "")


class StartIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    edition: str | None = Field(default=None, max_length=40)


@router.post("/{asm_id}/attempts")
def start(body: StartIn, asm_id: str = Path(pattern=r"^ASM-\d{4,6}$"), p: Principal = Depends(get_current),
          db: Session = Depends(get_db)):
    uid = p.user.id
    ratelimit.enforce(f"asm-start:{uid}", 60, 3600)
    ed, rel, a = _assessment(db, uid, body.edition, asm_id)
    q = select(AssessmentAttempt).where(AssessmentAttempt.user_id == uid, AssessmentAttempt.edition_id == ed,
                                        AssessmentAttempt.assessment_id == asm_id)
    for att in db.scalars(q).all():
        _expire_if_due(db, att)   # may commit, so it happens before the lock below
    db.commit()                   # end this snapshot: under REPEATABLE READ a plain read would not see a concurrent start
    # Two taps on Start must not create two attempts: lock this student's own user row for the rest of the transaction,
    # then read with a LOCKING read, which sees the latest committed rows. (Not a MySQL named lock: those belong to a
    # connection, and a commit can hand it back to the pool before release.)
    db.scalar(select(User.id).where(User.id == uid).with_for_update())
    mine = db.scalars(q.with_for_update()).all()
    open_ = next((x for x in mine if x.status == "in_progress"), None)
    if open_ is not None:
        return _view(open_, db, a.get("title", ""))
    last = max((x.submitted_at for x in mine if x.submitted_at), default=None)
    if last is not None and utcnow() < last + COOLDOWN:
        raise ApiError(409, "You can take this assessment again 24 hours after your last attempt.", "attempt_cooldown",
                       headers={"X-Next-Attempt-At": _iso(last + COOLDOWN)})
    seed = secrets.randbits(48)
    rng = random.Random(seed)
    rules = a.get("rules", {})
    ids = list(a["items"])
    if rules.get("shuffle_items"):
        rng.shuffle(ids)
    layout = []
    for i in ids:
        it = _public(db, rel.id, f"items/{i}.json")
        if it is None:
            raise ApiError(404, *_NOT_FOUND)
        opts = [o["id"] for o in it.get("options", [])]
        if rules.get("shuffle_options", True):
            rng.shuffle(opts)
        layout.append({"item_id": i, "options": opts})
    now = utcnow()
    att = AssessmentAttempt(user_id=uid, edition_id=ed, release_id=rel.id, assessment_id=asm_id, status="in_progress",
                            seed=seed, layout=layout, answers={}, seq=0, started_at=now, expires_at=now + SESSION,
                            auto_submitted=False)
    db.add(att)
    db.commit()
    return _view(att, db, a.get("title", ""))


@router.get("/{asm_id}/attempts")
def history(edition: str | None = None, asm_id: str = Path(pattern=r"^ASM-\d{4,6}$"), p: Principal = Depends(get_current),
            db: Session = Depends(get_db)):
    ed = entitled_edition(db, p.user.id, edition)
    rows = db.scalars(select(AssessmentAttempt).where(AssessmentAttempt.user_id == p.user.id, AssessmentAttempt.edition_id == ed,
                                                     AssessmentAttempt.assessment_id == asm_id)
                      .order_by(AssessmentAttempt.started_at)).all()
    for r in rows:
        _expire_if_due(db, r)
    last = max((r.submitted_at for r in rows if r.submitted_at), default=None)
    nxt = None if last is None or any(r.status == "in_progress" for r in rows) else last + COOLDOWN
    return {"assessment_id": asm_id, "next_attempt_at": _iso(nxt) if nxt and nxt > utcnow() else None,
            "attempts": [{"attempt_id": r.id, "status": r.status, "started_at": _iso(r.started_at), "submitted_at": _iso(r.submitted_at),
                          "score": r.score, "max_score": r.max_score, "auto_submitted": r.auto_submitted} for r in rows]}


@router.get("/attempts/{attempt_id}")
def get_attempt(attempt_id: str = Path(pattern=_UUID), p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    att = _own_attempt(db, p.user.id, attempt_id)
    _expire_if_due(db, att)
    return _view(att, db, _title(db, att))


class SaveIn(BaseModel):
    model_config = ConfigDict(extra="forbid")  # no score, no "correct", no user
    seq: int = Field(ge=1, le=1_000_000)
    answers: dict[str, str] = Field(max_length=200)


def _apply(att: AssessmentAttempt, body: SaveIn) -> None:
    allowed = {s["item_id"]: set(s["options"]) for s in att.layout}
    for item, opt in body.answers.items():
        if item not in allowed or opt not in allowed[item]:
            raise ApiError(422, "That answer doesn't belong to this assessment.", "invalid_answer")
    if body.seq <= att.seq:
        raise ApiError(409, "A newer save already arrived.", "stale_save", headers={"X-Current-Seq": str(att.seq)})
    att.answers, att.seq = dict(body.answers), body.seq


@router.put("/attempts/{attempt_id}/answers")
def save(body: SaveIn, attempt_id: str = Path(pattern=_UUID), p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    ratelimit.enforce(f"asm-save:{p.user.id}", 1200, 3600)
    att = _own_attempt(db, p.user.id, attempt_id, lock=True)
    _expire_if_due(db, att)
    if att.status != "in_progress":
        raise ApiError(409, "This attempt has already been submitted.", "already_submitted")
    if utcnow() > att.expires_at:
        raise ApiError(409, "Time is up for this attempt. Submit it to see your result.", "expired")
    _apply(att, body)
    db.commit()
    return {"attempt_id": att.id, "seq": att.seq, "saved": len(att.answers)}


class SubmitIn(BaseModel):
    model_config = ConfigDict(extra="forbid")
    request_id: str = Field(pattern=_UUID)
    seq: int | None = Field(default=None, ge=1, le=1_000_000)
    answers: dict[str, str] | None = Field(default=None, max_length=200)


@router.post("/attempts/{attempt_id}/submit")
def submit(body: SubmitIn, attempt_id: str = Path(pattern=_UUID), p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    ratelimit.enforce(f"asm-submit:{p.user.id}", 120, 3600)
    att = _own_attempt(db, p.user.id, attempt_id, lock=True)
    if att.status == "submitted":   # idempotent: a retried or replayed submit returns the same stored result
        return _view(att, db, _title(db, att))
    late = utcnow() > att.expires_at + GRACE
    if body.answers is not None and body.seq is not None and not late:
        try:
            _apply(att, SaveIn(seq=body.seq, answers=body.answers))
        except ApiError as e:
            if e.code != "stale_save":   # an older final save is ignored; the newer saved answers count
                raise
    _finalize(db, att, auto=late, request_id=body.request_id)
    db.commit()
    return _view(att, db, _title(db, att))


def summary(db: Session, uid: str, ed: str) -> list[dict]:
    """Best submitted score per assessment, for GET /v1/progress (the Me screen)."""
    rows = db.scalars(select(AssessmentAttempt).where(AssessmentAttempt.user_id == uid, AssessmentAttempt.edition_id == ed,
                                                     AssessmentAttempt.status == "submitted")).all()
    best: dict[str, dict] = {}
    for r in rows:
        b = best.setdefault(r.assessment_id, {"assessment_id": r.assessment_id, "best_score": 0, "max_score": r.max_score, "attempts": 0})
        b["attempts"] += 1
        b["best_score"] = max(b["best_score"], r.score or 0)
    return sorted(best.values(), key=lambda x: x["assessment_id"])
