"""Builds the App Content Package v0 from a source tree (docs/content-interface.md §2).

Public objects are built by ALLOWLISTING fields, so a new protected field can never leak by default. The leak check
(leakcheck.py) is an independent second line of defence. Release validation errors abort the build."""
import argparse
import base64
import hashlib
import json
import pathlib
import shutil
import sys

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from .leakcheck import check as leak_check

FORMAT = "cs23c02-package/0"
LESSON_FIELDS = ("id", "title", "topic", "minutes", "blocks", "quiz", "practice")
ITEM_FIELDS = ("id", "type", "purpose", "level", "stem", "code")  # code: the program a trace item asks about
EXR_FIELDS = ("id", "title", "statement", "io_spec", "starter", "python", "timeout_s")
ASM_FIELDS = ("id", "title", "purpose", "items", "rules")
VISIBILITIES = {"public", "hidden"}


class BuildError(Exception):
    pass


def canon(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def _load(dirpath: pathlib.Path):
    return {p.stem: json.loads(p.read_text()) for p in sorted(dirpath.glob("*.json"))}


def _pick(obj: dict, fields) -> dict:
    return {k: obj[k] for k in fields if k in obj}


def validate(book, nav, lessons, items, exrs, asms) -> list[str]:
    errs = []
    for k in ("id", "edition", "version", "min_app_version", "python"):
        if not book.get(k):
            errs.append(f"book.json missing {k}")
    listed = []
    for u in nav["units"]:
        for t in u["topics"]:
            listed += t["lessons"]
    for lid in listed:
        if lid not in lessons:
            errs.append(f"nav lists unknown lesson {lid}")
    if len(listed) != len(set(listed)):
        errs.append("a lesson appears twice in nav")
    for lid, l in lessons.items():
        if l["id"] != lid:
            errs.append(f"{lid}: file name and id differ")
        if l.get("minutes", 0) > 10:
            errs.append(f"{lid}: explanation longer than 10 minutes")
        if l.get("quiz") and l["quiz"] not in asms:
            errs.append(f"{lid}: unknown quiz {l['quiz']}")
        for i in l.get("practice", {}).get("items", []):
            if i not in items:
                errs.append(f"{lid}: unknown item {i}")
        for e in l.get("practice", {}).get("exercises", []):
            if e not in exrs:
                errs.append(f"{lid}: unknown exercise {e}")
    for iid, it in items.items():
        ids = [o["id"] for o in it.get("options", [])]
        if it["type"] in ("mcq", "trace") and (it.get("answer") not in ids or len(ids) != len(set(ids)) or len(ids) < 2):
            errs.append(f"{iid}: mcq needs unique option ids and exactly one valid answer")
        if not it.get("explanation"):
            errs.append(f"{iid}: missing explanation")
        if it.get("purpose") not in ("practice", "assessment"):
            errs.append(f"{iid}: purpose must be practice or assessment")
        if it["type"] == "trace" and not it.get("code"):
            errs.append(f"{iid}: a trace item needs the code it asks about")
    for eid, e in exrs.items():
        vis = [t.get("visibility") for t in e.get("tests", [])]
        if any(v not in VISIBILITIES for v in vis) or "public" not in vis or "hidden" not in vis:
            errs.append(f"{eid}: needs tests marked public/hidden, at least one of each")
        if not e.get("reference_solution"):
            errs.append(f"{eid}: missing reference_solution")
    for aid, a in asms.items():
        for i in a["items"]:
            if i not in items and i not in exrs:
                errs.append(f"{aid}: unknown item {i}")
    return errs


def build(src: pathlib.Path, out: pathlib.Path, private_key: Ed25519PrivateKey, signed_at: str = "") -> dict:
    book = json.loads((src / "book.json").read_text())
    nav = json.loads((src / "nav.json").read_text())
    lessons, items, exrs, asms = (_load(src / d) for d in ("lessons", "items", "exercises", "assessments"))
    errs = validate(book, nav, lessons, items, exrs, asms)
    if errs:
        raise BuildError("Release validation failed:\n  " + "\n  ".join(errs))

    public, protected = {}, {"items": {}, "exercises": {}}
    for i, l in lessons.items():
        public[f"lessons/{i}.json"] = _pick(l, LESSON_FIELDS)
    for i, it in items.items():
        pub = _pick(it, ITEM_FIELDS)
        pub["options"] = [{"id": o["id"], "text": o["text"]} for o in it["options"]]
        public[f"items/{i}.json"] = pub
        protected["items"][i] = {"answer": it["answer"], "explanation": it["explanation"],
                                 "option_explanations": it.get("option_explanations", {})}
    for i, e in exrs.items():
        pub = _pick(e, EXR_FIELDS)
        pub["tests"] = [{"stdin": t["stdin"], "expect": t["expect"]} for t in e["tests"] if t["visibility"] == "public"]
        public[f"exercises/{i}.json"] = pub
        protected["exercises"][i] = {"hidden_tests": [{"stdin": t["stdin"], "expect": t["expect"]} for t in e["tests"] if t["visibility"] == "hidden"],
                                     "hints": e.get("hints", []), "solution_approach": e.get("solution_approach", ""),
                                     "reference_solution": e["reference_solution"]}
    for i, a in asms.items():
        public[f"assessments/{i}.json"] = _pick(a, ASM_FIELDS)

    if out.exists():
        shutil.rmtree(out)
    (out / "public").mkdir(parents=True)
    files = []
    for rel, obj in sorted(public.items()):
        data = canon(obj)
        path = out / "public" / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        files.append({"path": rel, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)})
    manifest = {"format": FORMAT, "book": {k: book[k] for k in ("id", "title", "edition", "version", "min_app_version", "python")},
                "signed_at": signed_at, "nav": nav, "files": files}
    mbytes = canon(manifest)
    (out / "public" / "manifest.json").write_bytes(mbytes)
    (out / "public" / "manifest.sig").write_text(base64.b64encode(private_key.sign(mbytes)).decode())
    (out / "protected.json").write_bytes(canon(protected))

    leaks = leak_check(out / "public", protected)
    if leaks:
        shutil.rmtree(out)
        raise BuildError("Protected content found in the public package:\n  " + "\n  ".join(leaks))
    return manifest


def new_keypair() -> tuple[str, str]:
    k = Ed25519PrivateKey.generate()
    priv = k.private_bytes(serialization.Encoding.Raw, serialization.PrivateFormat.Raw, serialization.NoEncryption())
    pub = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return base64.b64encode(priv).decode(), base64.b64encode(pub).decode()


def load_private(b64: str) -> Ed25519PrivateKey:
    return Ed25519PrivateKey.from_private_bytes(base64.b64decode(b64))


def main(argv=None):
    p = argparse.ArgumentParser(prog="python -m cs23c02_content.build")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("keygen", help="print a new signing keypair (keep the private key secret)")
    b = sub.add_parser("build")
    b.add_argument("src"); b.add_argument("out"); b.add_argument("--key-file", required=True, help="file holding the base64 private key")
    a = p.parse_args(argv)
    if a.cmd == "keygen":
        priv, pub = new_keypair()
        print("PRIVATE (secret, never commit):", priv)
        print("PUBLIC  (CONTENT_SIGNING_PUBKEY):", pub)
        return
    try:
        m = build(pathlib.Path(a.src), pathlib.Path(a.out), load_private(pathlib.Path(a.key_file).read_text().strip()))
    except BuildError as e:
        sys.exit(str(e))
    print(f"built {m['book']['id']} {m['book']['version']}: {len(m['files'])} public files -> {a.out}/public (protected set: {a.out}/protected.json — never publish)")


if __name__ == "__main__":
    main()
