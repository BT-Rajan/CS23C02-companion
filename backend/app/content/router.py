import pathlib
import re

from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.orm import Session

from .. import ratelimit
from ..config import get_settings
from ..db import get_db
from ..deps import Principal, get_current
from ..entitlements import entitled_edition, entitled_edition_ids
from ..errors import ApiError
from ..models import ContentFile, ContentRelease
from .importer import current_release

router = APIRouter(prefix="/v1/content", tags=["content"])
_SHA = re.compile(r"^[0-9a-f]{64}$")


@router.get("/manifest")
def manifest(edition: str | None = Query(default=None, max_length=40), p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    """The exact signed manifest bytes; the app verifies X-Manifest-Signature before trusting anything in it."""
    ed = entitled_edition(db, p.user.id, edition)
    rel = current_release(db, ed)
    if rel is None:
        raise ApiError(404, "Content isn't available yet.", "no_content")
    return Response(rel.manifest, media_type="application/json",
                    headers={"X-Manifest-Signature": rel.signature, "X-Content-Version": rel.version, "X-Edition": ed})


@router.get("/files/{sha256}")
def file(sha256: str, p: Principal = Depends(get_current), db: Session = Depends(get_db)):
    """Content-addressed download. A hash is served only if it belongs to a non-draft release of an edition this account owns;
    anything else is the same 404, so hashes cannot be probed."""
    ratelimit.enforce(f"files:{p.user.id}", 2000, 3600)  # scraping brake (attack C6)
    if not _SHA.match(sha256):
        raise ApiError(404, "Not found.", "not_found")
    owned = entitled_edition_ids(db, p.user.id)
    hit = db.scalar(select(ContentFile.sha256).join(ContentRelease, ContentRelease.id == ContentFile.release_id)
                    .where(ContentFile.sha256 == sha256, ContentRelease.status.in_(("published", "superseded")),
                           ContentRelease.edition_id.in_(owned or [""])).limit(1))
    path = pathlib.Path(get_settings().content_dir) / "files" / sha256
    if hit is None or not path.is_file():
        raise ApiError(404, "Not found.", "not_found")
    return Response(path.read_bytes(), media_type="application/octet-stream",
                    headers={"Cache-Control": "private, max-age=31536000, immutable"})
