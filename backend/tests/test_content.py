import base64
import hashlib
import json
import pathlib
import shutil

import pytest
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from sqlalchemy import select

from app.content import importer
from app.db import SessionLocal
from app.models import ContentRelease, ProtectedContent
from tests.conftest import SIGNING, build_package, import_fixture, ip_headers, owner, signed_in, grant, ensure_edition

BACKEND_DIR = pathlib.Path(__file__).resolve().parents[1]


def _import(pub, prot, edition="ed1", publish=False):
    ensure_edition(edition)
    with SessionLocal() as db:
        return importer.import_release(db, edition, pub, prot, publish=publish)


# ---- importer: nothing unsigned, altered, unlisted or leaking gets in ---------------------------------------------------
def test_leakcheck_copies_are_identical():
    a = (BACKEND_DIR / "app/content/leakcheck.py").read_bytes()
    b = (BACKEND_DIR.parent / "content-tools/cs23c02_content/leakcheck.py").read_bytes()
    assert a == b, "keep the two leak checks in sync"


def test_importer_rejects_tampering_in_every_form():
    cases = {}
    pub, prot = build_package("1.0.0"); (pub / "manifest.json").write_bytes((pub / "manifest.json").read_bytes().replace(b"1.0.0", b"6.6.6")); cases["manifest edited"] = (pub, prot)
    pub, prot = build_package("1.0.1"); f = next((pub / "lessons").glob("*.json")); f.write_text(f.read_text().replace("variable", "VARIABLE", 1)); cases["file edited"] = (pub, prot)
    pub, prot = build_package("1.0.2"); (pub / "lessons" / "LSN-9999.json").write_text("{}"); cases["unlisted file"] = (pub, prot)
    pub, prot = build_package("1.0.3"); next((pub / "items").glob("*.json")).unlink(); cases["missing file"] = (pub, prot)
    pub, prot = build_package("1.0.4", priv=__import__("cs23c02_content.build", fromlist=["x"]).new_keypair()[0]); cases["signed by someone else"] = (pub, prot)
    for name, (p, q) in cases.items():
        with pytest.raises(importer.PackageError):
            _import(p, q)
    with SessionLocal() as db:
        assert db.scalar(select(ContentRelease)) is None  # nothing half-imported


def test_importer_rejects_a_validly_signed_package_with_path_traversal_or_a_leak():
    from cs23c02_content import build as B
    pub, prot = build_package("2.0.0")
    m = json.loads((pub / "manifest.json").read_text())
    m["files"][0]["path"] = "../../etc/passwd"
    mb = B.canon(m)
    (pub / "manifest.json").write_bytes(mb)
    (pub / "manifest.sig").write_text(base64.b64encode(B.load_private(SIGNING["priv"]).sign(mb)).decode())
    with pytest.raises(importer.PackageError, match="unsafe"):
        _import(pub, prot)
    # a leak that the build would have stopped, forged and re-signed: the importer still refuses
    pub, prot = build_package("2.0.1")
    p = pub / "items/ITM-0001.json"
    d = json.loads(p.read_text()); d["answer"] = "b"; data = B.canon(d); p.write_bytes(data)
    m = json.loads((pub / "manifest.json").read_text())
    for f in m["files"]:
        if f["path"] == "items/ITM-0001.json":
            f["sha256"], f["size"] = hashlib.sha256(data).hexdigest(), len(data)
    mb = B.canon(m); (pub / "manifest.json").write_bytes(mb)
    (pub / "manifest.sig").write_text(base64.b64encode(B.load_private(SIGNING["priv"]).sign(mb)).decode())
    with pytest.raises(importer.PackageError, match="protected content"):
        _import(pub, prot)


def test_duplicate_version_and_unknown_edition_are_refused_and_publishing_supersedes():
    r1 = import_fixture("ed1", "3.0.0", publish=True)
    with pytest.raises(importer.PackageError, match="already imported"):
        import_fixture("ed1", "3.0.0")
    pub, prot = build_package("3.0.1")
    with SessionLocal() as db, pytest.raises(importer.PackageError, match="unknown edition"):
        importer.import_release(db, "nope", pub, prot)
    r2 = import_fixture("ed1", "3.1.0", publish=True)
    with SessionLocal() as db:
        st = {r.version: r.status for r in db.scalars(select(ContentRelease))}
        assert st == {"3.0.0": "superseded", "3.1.0": "published"}
        assert db.scalar(select(ProtectedContent).where(ProtectedContent.release_id == r2, ProtectedContent.content_id == "ITM-0001")).data["answer"] == "b"


# ---- serving: authenticated, entitled, uniform failures ------------------------------------------------------------------
def test_content_requires_login_and_activation(client):
    import_fixture()
    assert client.get("/v1/content/manifest").status_code == 401
    assert client.get("/v1/content/files/" + "a" * 64).status_code == 401
    _, _, h = signed_in(client)
    assert client.get("/v1/content/manifest", headers=h).status_code == 403
    assert client.get("/v1/content/manifest?edition=ed1", headers=h).status_code == 403


def test_unknown_edition_and_someone_elses_edition_look_identical(client):
    import_fixture("ed1")
    _, h = owner(client, "ed2")  # owns ed2, not ed1
    a = client.get("/v1/content/manifest?edition=ed1", headers=h)
    b = client.get("/v1/content/manifest?edition=does-not-exist", headers=h)
    assert (a.status_code, a.json()) == (b.status_code, b.json()) == (403, a.json())


def test_manifest_is_the_exact_signed_bytes_and_the_app_can_verify_it(client):
    import_fixture()
    _, h = owner(client)
    r = client.get("/v1/content/manifest", headers=h)  # edition omitted: the account's only book
    assert r.status_code == 200 and r.headers["x-content-version"] == "0.1.0"
    Ed25519PublicKey.from_public_bytes(base64.b64decode(SIGNING["pub"])).verify(base64.b64decode(r.headers["x-manifest-signature"]), r.content)
    m = r.json()
    for f in m["files"]:
        got = client.get("/v1/content/files/" + f["sha256"], headers=h)
        assert got.status_code == 200 and hashlib.sha256(got.content).hexdigest() == f["sha256"]
        assert "immutable" in got.headers["cache-control"] and "private" in got.headers["cache-control"]


def test_nothing_protected_is_reachable_through_any_served_file(client):
    import_fixture()
    _, h = owner(client)
    pub, prot = build_package("0.1.0")
    protected = json.loads(prot.read_text())
    m = client.get("/v1/content/manifest", headers=h).json()
    served = "".join(client.get("/v1/content/files/" + f["sha256"], headers=h).text for f in m["files"]) + json.dumps(m)
    checked = 0
    for bucket in protected.values():
        for obj in bucket.values():
            for v in json.dumps(obj, ensure_ascii=False).replace("{", " ").replace("}", " ").split('"'):
                if len(v) >= 12 and v.strip() not in ("", ":", ","):
                    assert v not in served, v
                    checked += 1
    assert checked >= 15  # the test really compared the protected strings


def test_files_of_another_edition_or_unknown_hashes_are_a_uniform_404(client):
    import_fixture("ed1", "0.1.0")
    import_fixture("ed2", "0.2.0", mutate=lambda src: (src / "lessons/LSN-0001.json").write_text(
        (src / "lessons/LSN-0001.json").read_text().replace("What is a variable?", "ED2 ONLY TITLE")))
    _, h2 = owner(client, "ed2")
    with SessionLocal() as db:
        ed1_hash = db.scalar(select(__import__("app.models", fromlist=["x"]).ContentFile.sha256).join(ContentRelease).where(
            ContentRelease.edition_id == "ed1", __import__("app.models", fromlist=["x"]).ContentFile.path == "lessons/LSN-0001.json"))
    r_other = client.get("/v1/content/files/" + ed1_hash, headers=h2)
    r_none = client.get("/v1/content/files/" + "0" * 64, headers=h2)
    r_bad = client.get("/v1/content/files/not-a-hash", headers=h2)
    assert {r_other.status_code, r_none.status_code, r_bad.status_code} == {404}
    assert r_other.json() == r_none.json() == r_bad.json()


def test_drafts_are_not_served_superseded_files_still_are_and_revoked_access_stops(client):
    import_fixture("ed1", "1.0.0", publish=False)
    u, h = owner(client)
    assert client.get("/v1/content/manifest", headers=h).status_code == 404  # draft only
    import_fixture("ed1", "1.1.0", publish=True)
    first = {f["path"]: f["sha256"] for f in client.get("/v1/content/manifest", headers=h).json()["files"]}
    import_fixture("ed1", "1.2.0", publish=True,
                   mutate=lambda src: (src / "lessons/LSN-0001.json").write_text((src / "lessons/LSN-0001.json").read_text().replace("name that", "label that")))
    assert client.get("/v1/content/manifest", headers=h).headers["x-content-version"] == "1.2.0"
    assert client.get("/v1/content/files/" + first["lessons/LSN-0001.json"], headers=h).status_code == 200  # mid-download across an update
    from app.models import Entitlement
    with SessionLocal() as db:
        from app.db import utcnow
        db.query(Entitlement).filter(Entitlement.user_id == u.id).update({"revoked_at": utcnow()})
        db.commit()
    assert client.get("/v1/content/manifest", headers=h).status_code == 403
    assert client.get("/v1/content/files/" + first["lessons/LSN-0001.json"], headers=h).status_code == 404
