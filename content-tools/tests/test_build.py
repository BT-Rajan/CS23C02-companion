import base64
import copy
import hashlib
import json
import pathlib
import shutil

import pytest
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

from cs23c02_content import build as B
from cs23c02_content.leakcheck import check, forbidden_keys_in

FIX = pathlib.Path(__file__).resolve().parents[1] / "fixtures" / "cs23c02-fixture"


@pytest.fixture
def keys():
    return B.new_keypair()


@pytest.fixture
def built(tmp_path, keys):
    priv, pub = keys
    m = B.build(FIX, tmp_path / "out", B.load_private(priv))
    return tmp_path / "out", m, pub


def test_build_produces_signed_manifest_and_hashed_files(built):
    out, m, pub = built
    mbytes = (out / "public" / "manifest.json").read_bytes()
    Ed25519PublicKey.from_public_bytes(base64.b64decode(pub)).verify(base64.b64decode((out / "public" / "manifest.sig").read_text()), mbytes)
    assert m["book"]["id"] == "BOK-0001" and len(m["files"]) == 3 + 5 + 2 + 1
    for f in m["files"]:
        assert hashlib.sha256((out / "public" / f["path"]).read_bytes()).hexdigest() == f["sha256"]


def test_a_tampered_manifest_fails_verification(built):
    out, _, pub = built
    mbytes = (out / "public" / "manifest.json").read_bytes().replace(b"0.1.0", b"9.9.9")
    with pytest.raises(InvalidSignature):
        Ed25519PublicKey.from_public_bytes(base64.b64decode(pub)).verify(base64.b64decode((out / "public" / "manifest.sig").read_text()), mbytes)


def test_public_package_has_no_answers_hints_solutions_or_hidden_tests(built):
    out, _, _ = built
    protected = json.loads((out / "protected.json").read_text())
    everything = "".join(p.read_text() for p in (out / "public").rglob("*") if p.is_file())
    for needle in ("FIXTURE-EXPLAIN", "FIXTURE-HINT", "FIXTURE-APPROACH", "FIXTURE-REFERENCE-SOLUTION", '"answer"', "hidden", "-6\\n"):
        assert needle not in everything, needle
    assert check(out / "public", protected) == []
    item = json.loads((out / "public/items/ITM-0001.json").read_text())
    assert set(item) == {"id", "type", "purpose", "level", "stem", "options"} and all(set(o) == {"id", "text"} for o in item["options"])
    exr = json.loads((out / "public/exercises/EXR-0001.json").read_text())
    assert exr["tests"] == [{"stdin": "4\n", "expect": "8\n"}]  # only the public example


def test_protected_set_holds_what_the_server_needs(built):
    out, _, _ = built
    p = json.loads((out / "protected.json").read_text())
    assert p["items"]["ITM-0001"]["answer"] == "b" and "FIXTURE-EXPLAIN-1" in p["items"]["ITM-0001"]["explanation"]
    assert len(p["exercises"]["EXR-0001"]["hidden_tests"]) == 2 and len(p["exercises"]["EXR-0001"]["hints"]) == 2


def test_new_unlisted_source_fields_do_not_leak_because_the_split_is_an_allowlist(tmp_path, keys):
    src = tmp_path / "src"
    shutil.copytree(FIX, src)
    it = json.loads((src / "items/ITM-0001.json").read_text())
    it["teacher_note"] = "SECRET-NOTE-ADDED-LATER-BY-AN-AUTHOR"
    (src / "items/ITM-0001.json").write_text(json.dumps(it))
    B.build(src, tmp_path / "o", B.load_private(keys[0]))
    assert "SECRET-NOTE" not in "".join(p.read_text() for p in (tmp_path / "o/public").rglob("*") if p.is_file())


def test_leak_check_catches_a_leak_injected_after_the_split(built):
    out, _, _ = built
    protected = json.loads((out / "protected.json").read_text())
    p = out / "public/items/ITM-0002.json"
    d = json.loads(p.read_text())
    d["stem"] += " (hint: " + protected["items"]["ITM-0002"]["explanation"] + ")"
    p.write_text(json.dumps(d))
    assert any("protected text" in x for x in check(out / "public", protected))
    d2 = json.loads(p.read_text()); d2["answer"] = "c"; p.write_text(json.dumps(d2))
    assert any("forbidden key" in x for x in check(out / "public", protected))
    assert forbidden_keys_in({"a": [{"Is_Correct": True}]}) == ["$.a[0].Is_Correct"]


@pytest.mark.parametrize("mutate,expect", [
    (lambda d: d["items"]["ITM-0001"].update(answer="zzz"), "exactly one valid answer"),
    (lambda d: d["lessons"]["LSN-0001"].update(minutes=11), "longer than 10 minutes"),
    (lambda d: d["lessons"]["LSN-0001"].update(quiz="ASM-9999"), "unknown quiz"),
    (lambda d: [t.update(visibility="public") for t in d["exercises"]["EXR-0001"]["tests"]], "public/hidden"),
    (lambda d: d["exercises"]["EXR-0002"].pop("reference_solution"), "missing reference_solution"),
    (lambda d: d["nav"]["units"][0]["topics"][0]["lessons"].append("LSN-7777"), "unknown lesson"),
])
def test_release_validation_rejects_bad_source(tmp_path, keys, mutate, expect):
    src = tmp_path / "src"
    shutil.copytree(FIX, src)
    d = {"items": {}, "lessons": {}, "exercises": {}, "nav": json.loads((src / "nav.json").read_text())}
    for k, sub in (("items", "items"), ("lessons", "lessons"), ("exercises", "exercises")):
        for f in (src / sub).glob("*.json"):
            d[k][f.stem] = json.loads(f.read_text())
    mutate(d)
    for k, sub in (("items", "items"), ("lessons", "lessons"), ("exercises", "exercises")):
        for stem, obj in d[k].items():
            (src / sub / f"{stem}.json").write_text(json.dumps(obj))
    (src / "nav.json").write_text(json.dumps(d["nav"]))
    with pytest.raises(B.BuildError, match=expect):
        B.build(src, tmp_path / "o", B.load_private(keys[0]))


def test_trace_item_ships_its_code_but_never_its_answer(built):
    out, _, _ = built
    pub = json.loads((out / "public" / "items" / "ITM-0004.json").read_text())
    assert pub["type"] == "trace" and "print('odd')" in pub["code"]
    assert set(pub) == {"id", "type", "purpose", "level", "stem", "code", "options"}
    prot = json.loads((out / "protected.json").read_text())["items"]["ITM-0004"]
    assert prot["answer"] == "b" and prot["explanation"].startswith("FIXTURE-EXPLAIN-4")


def test_trace_item_without_code_fails_release_validation(tmp_path, keys):
    src = tmp_path / "src"
    shutil.copytree(FIX, src)
    it = json.loads((src / "items" / "ITM-0004.json").read_text())
    del it["code"]
    (src / "items" / "ITM-0004.json").write_text(json.dumps(it))
    with pytest.raises(B.BuildError, match="trace item needs the code"):
        B.build(src, tmp_path / "out", B.load_private(keys[0]))
