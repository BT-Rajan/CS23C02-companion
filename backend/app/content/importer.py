"""Imports a signed App Content Package. Defence in depth: signature, per-file hashes, exact file set, safe paths, and the leak
check are all re-verified here even though the build already did them."""
import base64
import hashlib
import json
import os
import pathlib
import re
import tempfile

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from sqlalchemy import select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from ..config import get_settings
from ..db import utcnow
from ..models import ContentFile, ContentLesson, ContentRelease, Edition, ProtectedContent
from . import leakcheck

FORMAT = "cs23c02-package/0"
_PATH = re.compile(r"^(lessons|items|exercises|assessments)/[A-Z]{3}-\d{4,6}\.json$")


class PackageError(Exception):
    pass


def verify_package(public_dir: pathlib.Path, pubkey_b64: str) -> tuple[dict, bytes, str]:
    if not pubkey_b64:
        raise PackageError("CONTENT_SIGNING_PUBKEY is not configured")
    mbytes = (public_dir / "manifest.json").read_bytes()
    sig_b64 = (public_dir / "manifest.sig").read_text().strip()
    try:
        Ed25519PublicKey.from_public_bytes(base64.b64decode(pubkey_b64)).verify(base64.b64decode(sig_b64), mbytes)
    except (InvalidSignature, ValueError):
        raise PackageError("manifest signature is not valid")
    m = json.loads(mbytes)
    if m.get("format") != FORMAT:
        raise PackageError("unsupported package format")
    listed = {}
    for f in m["files"]:
        if not _PATH.match(f["path"]):
            raise PackageError(f"unsafe or unexpected path in manifest: {f['path']!r}")
        data = (public_dir / f["path"]).read_bytes() if (public_dir / f["path"]).is_file() else None
        if data is None:
            raise PackageError(f"listed file missing: {f['path']}")
        if len(data) != f["size"] or hashlib.sha256(data).hexdigest() != f["sha256"]:
            raise PackageError(f"file does not match its manifest entry: {f['path']}")
        listed[f["path"]] = f
    actual = {str(p.relative_to(public_dir)) for p in public_dir.rglob("*") if p.is_file()} - {"manifest.json", "manifest.sig"}
    if actual != set(listed):
        raise PackageError("package contains unlisted files: " + ", ".join(sorted(actual - set(listed))))
    return m, mbytes, sig_b64


def _store(files_dir: pathlib.Path, sha: str, data: bytes) -> None:
    dest = files_dir / sha
    if dest.exists():
        return
    files_dir.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=files_dir)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
    os.replace(tmp, dest)  # atomic; content-addressed so repeats are harmless


def import_release(db: Session, edition_id: str, public_dir: pathlib.Path, protected_path: pathlib.Path, publish: bool = False) -> int:
    s = get_settings()
    m, mbytes, sig = verify_package(public_dir, s.content_signing_pubkey)
    if not db.get(Edition, edition_id):
        raise PackageError(f"unknown edition {edition_id}")
    protected = json.loads(protected_path.read_text())
    leaks = leakcheck.check(public_dir, protected)
    if leaks:
        raise PackageError("protected content found in public package: " + "; ".join(leaks[:5]))

    files_dir = pathlib.Path(s.content_dir) / "files"
    for f in m["files"]:
        _store(files_dir, f["sha256"], (public_dir / f["path"]).read_bytes())

    book, now = m["book"], utcnow()
    try:
        rel = ContentRelease(edition_id=edition_id, version=book["version"], book_id=book["id"], manifest=mbytes,
                             manifest_sha256=hashlib.sha256(mbytes).hexdigest(), signature=sig, min_app_version=book["min_app_version"],
                             status="draft", created_at=now)
        db.add(rel)
        db.flush()
    except IntegrityError:
        db.rollback()
        raise PackageError(f"version {book['version']} already imported for {edition_id}")
    for f in m["files"]:
        db.add(ContentFile(release_id=rel.id, path=f["path"], sha256=f["sha256"], size=f["size"]))
    order = 0
    for unit in m["nav"]["units"]:
        for topic in unit["topics"]:
            for lid in topic["lessons"]:
                order += 1
                title = json.loads((public_dir / f"lessons/{lid}.json").read_text())["title"]
                db.add(ContentLesson(release_id=rel.id, lesson_id=lid, ord=order, title=title, unit_id=unit["id"], topic_id=topic["id"]))
    for kind, bucket in (("item", "items"), ("exercise", "exercises")):
        for cid, data in protected.get(bucket, {}).items():
            db.add(ProtectedContent(release_id=rel.id, kind=kind, content_id=cid, data=data))
    db.commit()
    if publish:
        publish_release(db, rel.id)
    return rel.id


def publish_release(db: Session, release_id: int) -> None:
    rel = db.get(ContentRelease, release_id)
    if rel is None:
        raise PackageError("unknown release")
    db.execute(update(ContentRelease).where(ContentRelease.edition_id == rel.edition_id, ContentRelease.status == "published",
                                            ContentRelease.id != rel.id).values(status="superseded"))
    rel.status, rel.published_at = "published", utcnow()
    db.commit()


def current_release(db: Session, edition_id: str) -> ContentRelease | None:
    return db.scalar(select(ContentRelease).where(ContentRelease.edition_id == edition_id, ContentRelease.status == "published")
                     .order_by(ContentRelease.id.desc()).limit(1))
