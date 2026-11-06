"""Practice checking (ADR 0009, roadmap A5). Practice teaches; assessments measure.

POST /v1/practice/items/{item_id}/answer  {"option": "b", "edition": optional}

- Only items of the account's current published release, and only items whose public purpose is "practice". Assessment
  items get the same 404 as unknown ones, so this endpoint can never be used to learn assessment keys (attack D1).
- One question per call, verdict for that question only; keys are never sent in bulk (attack D7).
- Wrong answer: the verdict and, if the author wrote one, why that option is wrong. The correct option and the full
  explanation are released after REVEAL_AFTER wrong attempts at that item, or straight away on a right answer.
- Rate limits per account and per account+item slow down scripted harvesting.
- Nothing here changes progress or scores: practice results are not recorded as achievements.
"""
import json
import pathlib
import re

from fastapi import APIRouter, Depends, Path
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.orm import Session

from . import ratelimit
from .config import get_settings
from .content.importer import current_release
from .db import get_db
from .deps import Principal, get_current
from .entitlements import entitled_edition
from .errors import ApiError
from .models import ContentFile, ProtectedContent

router = APIRouter(prefix="/v1/practice", tags=["practice"])
REVEAL_AFTER = 2
CHECKABLE = {"mcq", "trace"}
_NOT_FOUND = ("This question isn't available for practice.", "not_found")


class AnswerIn(BaseModel):
    model_config = ConfigDict(extra="forbid")  # no user id, no "correct" flag, nothing the server works out itself
    option: str = Field(min_length=1, max_length=16, pattern=r"^[A-Za-z0-9_-]+$")
    edition: str | None = Field(default=None, max_length=40)


def _public_item(db: Session, release_id: int, item_id: str) -> dict | None:
    sha = db.scalar(select(ContentFile.sha256).where(ContentFile.release_id == release_id, ContentFile.path == f"items/{item_id}.json"))
    if sha is None:
        return None
    path = pathlib.Path(get_settings().content_dir) / "files" / sha
    return json.loads(path.read_text()) if path.is_file() else None


@router.post("/items/{item_id}/answer")
def answer(body: AnswerIn, item_id: str = Path(pattern=r"^ITM-\d{4,6}$"), p: Principal = Depends(get_current),
           db: Session = Depends(get_db)):
    uid = p.user.id
    ratelimit.enforce(f"practice:{uid}", 300, 3600)
    ed = entitled_edition(db, uid, body.edition)
    rel = current_release(db, ed)
    if rel is None:
        raise ApiError(404, "Content isn't available yet.", "no_content")
    item = _public_item(db, rel.id, item_id)
    if item is None or item.get("purpose") != "practice" or item.get("type") not in CHECKABLE:
        raise ApiError(404, *_NOT_FOUND)
    if body.option not in {o["id"] for o in item.get("options", [])}:
        raise ApiError(422, "Please choose one of the options.", "invalid_option")
    prot = db.scalar(select(ProtectedContent.data).where(ProtectedContent.release_id == rel.id, ProtectedContent.kind == "item",
                                                       ProtectedContent.content_id == item_id))
    if prot is None:
        raise ApiError(404, *_NOT_FOUND)
    ratelimit.enforce(f"practice-item:{uid}:{rel.id}:{item_id}", 30, 3600)

    correct = body.option == prot["answer"]
    wrong_key = f"practice-wrong:{uid}:{rel.id}:{item_id}"
    if not correct:
        ratelimit.record(wrong_key)
    wrong_so_far = ratelimit.count(wrong_key, 7 * 86400)
    reveal = correct or wrong_so_far >= REVEAL_AFTER
    out = {"item_id": item_id, "correct": correct, "attempts_wrong": wrong_so_far,
           "why": (prot.get("option_explanations") or {}).get(body.option) if not correct else None,
           "revealed": reveal}
    if reveal:
        out["answer"] = prot["answer"]
        out["explanation"] = prot["explanation"]
    return out
