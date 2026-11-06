"""Programming exercises (roadmap A6; ADR 0006 §3, ADR 0009; open question #3 approved).

POST /v1/exercises/{id}/submissions  {"code": "...", "edition": optional}
  Runs the code against the public AND hidden tests in the sandbox. Public tests come back in full (input, expected, what the
  program printed); hidden tests only as a count, plus the kind of failure ("wrong output", "ran too long") of the first one
  that failed - never their input or expected output. Every check is stored (server-verified outcome).
GET  /v1/exercises/{id}/help
  Hints and the solution, released by policy: hint 1 after 1 failed check, hint 2 after 3, each further hint after one more,
  and the solution after 5 failed checks or once the exercise is passed. Every release is audited.
"""
import hashlib
import json
import pathlib

from fastapi import APIRouter, Depends, Path, Request
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, select
from sqlalchemy.exc import OperationalError
from sqlalchemy.orm import Session

from . import ratelimit, sandbox_client
from .audit import audit
from .config import get_settings
from .content.importer import current_release
from .db import get_db, utcnow
from .deps import Principal, client_ip_hash, get_current
from .entitlements import entitled_edition
from .errors import ApiError
from .models import ContentFile, ExerciseAttempt, ProtectedContent, User

router = APIRouter(prefix="/v1/exercises", tags=["exercises"])
HINT_AFTER = (1, 3)       # failed checks before hint 1, hint 2; later hints one more each
SOLUTION_AFTER = 5
MAX_CODE = 20000
_NOT_FOUND = ("This exercise isn't available.", "not_found")


class SubmissionIn(BaseModel):
    model_config = ConfigDict(extra="forbid")  # no verdict, no user id
    code: str = Field(min_length=1, max_length=MAX_CODE)
    edition: str | None = Field(default=None, max_length=40)


def _exercise(db: Session, uid: str, edition: str | None, exercise_id: str):
    ed = entitled_edition(db, uid, edition)
    rel = current_release(db, ed)
    if rel is None:
        raise ApiError(404, "Content isn't available yet.", "no_content")
    sha = db.scalar(select(ContentFile.sha256).where(ContentFile.release_id == rel.id, ContentFile.path == f"exercises/{exercise_id}.json"))
    prot = db.scalar(select(ProtectedContent.data).where(ProtectedContent.release_id == rel.id, ProtectedContent.kind == "exercise",
                                                       ProtectedContent.content_id == exercise_id))
    path = pathlib.Path(get_settings().content_dir) / "files" / (sha or "missing")
    if sha is None or prot is None or not path.is_file():
        raise ApiError(404, *_NOT_FOUND)
    return ed, rel, json.loads(path.read_text()), prot


def _counts(db: Session, uid: str, ed: str, exercise_id: str) -> tuple[int, bool]:
    failed = db.scalar(select(func.count()).select_from(ExerciseAttempt).where(
        ExerciseAttempt.user_id == uid, ExerciseAttempt.edition_id == ed, ExerciseAttempt.exercise_id == exercise_id,
        ExerciseAttempt.passed.is_(False))) or 0
    passed = db.scalar(select(func.count()).select_from(ExerciseAttempt).where(
        ExerciseAttempt.user_id == uid, ExerciseAttempt.edition_id == ed, ExerciseAttempt.exercise_id == exercise_id,
        ExerciseAttempt.passed.is_(True))) or 0
    return failed, passed > 0


def hints_unlocked(failed: int, n_hints: int) -> int:
    thresholds = list(HINT_AFTER) + [HINT_AFTER[-1] + i for i in range(1, max(0, n_hints - len(HINT_AFTER)) + 1)]
    return sum(1 for t in thresholds[:n_hints] if failed >= t)


def _help_state(failed: int, passed: bool, n_hints: int) -> dict:
    unlocked = n_hints if passed else hints_unlocked(failed, n_hints)
    thresholds = list(HINT_AFTER) + [HINT_AFTER[-1] + i for i in range(1, n_hints + 1)]
    nxt = None if unlocked >= n_hints else thresholds[unlocked] - failed
    return {"failed_checks": failed, "passed": passed, "hints_unlocked": unlocked, "hints_total": n_hints,
            "next_hint_in": nxt, "solution_unlocked": passed or failed >= SOLUTION_AFTER,
            "solution_in": None if (passed or failed >= SOLUTION_AFTER) else SOLUTION_AFTER - failed}


def _kind(t: dict) -> str:
    return {"ok": "wrong output", "timeout": "ran too long", "memory": "used too much memory",
            "output_limit": "printed too much", "error": "stopped with an error"}.get(t["status"], "did not run")


@router.post("/{exercise_id}/submissions")
def submit(body: SubmissionIn, request: Request, exercise_id: str = Path(pattern=r"^EXR-\d{4,6}$"),
           p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    uid = p.user.id
    ratelimit.enforce(f"exr:{uid}", 60, 3600)
    ed, rel, pub, prot = _exercise(db, uid, body.edition, exercise_id)
    ratelimit.enforce(f"exr-item:{uid}:{exercise_id}", 20, 3600)
    public = [{"stdin": t["stdin"], "expect": t["expect"]} for t in pub.get("tests", [])]
    hidden = [{"stdin": t["stdin"], "expect": t["expect"]} for t in prot.get("hidden_tests", [])]
    # One check per student at a time (ADR 0006: per-user concurrency 1): lock this student's user row for the transaction,
    # failing at once if another check holds it. A transaction lock is released by commit, rollback or a dropped connection,
    # so a crashed request can never leave the student locked out.
    try:
        db.scalar(select(User.id).where(User.id == uid).with_for_update(nowait=True))
    except OperationalError:
        db.rollback()
        raise ApiError(429, "Your previous check is still running. Wait for it to finish.", "check_in_progress")
    result = sandbox_client.grade(body.code, public + hidden)
    runs = result["tests"]
    pub_runs, hid_runs = runs[: len(public)], runs[len(public):]
    passed = result["passed"] == result["total"]
    db.add(ExerciseAttempt(user_id=uid, edition_id=ed, release_id=rel.id, exercise_id=exercise_id, passed=passed,
                           tests_passed=result["passed"], tests_total=result["total"],
                           code_sha256=hashlib.sha256(body.code.encode()).hexdigest(), created_at=utcnow()))
    db.commit()
    failed, ever_passed = _counts(db, uid, ed, exercise_id)
    first_hidden_fail = next((r for r in hid_runs if not r["passed"]), None)
    return {
        "exercise_id": exercise_id, "passed": passed,
        "public": [{"stdin": t["stdin"], "expect": t["expect"], "got": r["stdout"], "passed": r["passed"], "status": r["status"],
                    "error": r["error"]} for t, r in zip(public, pub_runs)],
        "hidden": {"passed": sum(r["passed"] for r in hid_runs), "total": len(hid_runs),
                   "first_failure": None if first_hidden_fail is None else _kind(first_hidden_fail)},
        "help": _help_state(failed, ever_passed, len(prot.get("hints", []))),
    }


@router.get("/{exercise_id}/help")
def help_(request: Request, edition: str | None = None, exercise_id: str = Path(pattern=r"^EXR-\d{4,6}$"),
          p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    uid = p.user.id
    ratelimit.enforce(f"exr-help:{uid}", 120, 3600)
    ed, rel, pub, prot = _exercise(db, uid, edition, exercise_id)
    hints = prot.get("hints", [])
    failed, passed = _counts(db, uid, ed, exercise_id)
    state = _help_state(failed, passed, len(hints))
    out = {**state, "hints": hints[: state["hints_unlocked"]], "solution": None}
    if state["solution_unlocked"]:
        out["solution"] = {"approach": prot.get("solution_approach", ""), "code": prot.get("reference_solution", "")}
    audit("exercise_help", "solution" if out["solution"] else f"hints:{state['hints_unlocked']}", uid, f"{ed}:{exercise_id}",
          client_ip_hash(request))
    return out
