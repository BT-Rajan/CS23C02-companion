"""Independent check that nothing protected is in the public package. Runs inside the build and again in the backend importer.
Two rules: (1) no forbidden key names anywhere in public JSON; (2) no protected string of 12+ characters appears verbatim.
Short protected values (e.g. an answer 'b') are covered by rule 1 and by the allowlist construction, not by substring search."""
import json
import pathlib

FORBIDDEN_KEYS = {"answer", "answers", "explanation", "option_explanations", "hints", "hint", "solution", "solution_approach",
                  "reference_solution", "hidden_tests", "hidden", "visibility", "key", "correct", "is_correct"}
MIN_LEN = 12


def forbidden_keys_in(obj, path="$") -> list[str]:
    found = []
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k.lower() in FORBIDDEN_KEYS:
                found.append(f"{path}.{k}")
            found += forbidden_keys_in(v, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            found += forbidden_keys_in(v, f"{path}[{i}]")
    return found


def _strings(obj):
    if isinstance(obj, str):
        yield obj
    elif isinstance(obj, dict):
        for v in obj.values():
            yield from _strings(v)
    elif isinstance(obj, list):
        for v in obj:
            yield from _strings(v)


def check(public_dir: pathlib.Path, protected: dict) -> list[str]:
    problems, texts = [], {}
    for p in sorted(public_dir.rglob("*.json")):
        if p.name == "manifest.json" and p.parent == public_dir:
            obj = json.loads(p.read_text())
            obj = {k: v for k, v in obj.items() if k != "files"}
        else:
            obj = json.loads(p.read_text())
        rel = str(p.relative_to(public_dir))
        problems += [f"{rel}: forbidden key {k}" for k in forbidden_keys_in(obj)]
        texts[rel] = json.dumps(obj, ensure_ascii=False)
        texts[rel + "#raw"] = p.read_text()
    secrets_ = {s for s in _strings(protected) if len(s) >= MIN_LEN}
    for rel, text in texts.items():
        for s in secrets_:
            if s in text or json.dumps(s, ensure_ascii=False)[1:-1] in text:
                problems.append(f"{rel.replace('#raw', '')}: contains protected text {s[:30]!r}…")
    return sorted(set(problems))
