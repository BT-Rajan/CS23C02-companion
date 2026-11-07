"""Builds android/app/src/main/assets/stepper/demos.json from the demo programs: real recordings (stepper) plus
line explanations, shapes and book references (tutor/explain.py).
Run after changing a demo or the recorder: python runtime/demos/build_demos.py. A test fails if the asset is stale."""
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "stepper"))
sys.path.insert(0, str(HERE.parent / "tutor"))
import explain  # noqa: E402
import stepper  # noqa: E402

ASSET = HERE.parents[1] / "android" / "app" / "src" / "main" / "assets" / "stepper" / "demos.json"


def build() -> str:
    out = []
    for d in json.loads((HERE / "demos.json").read_text()):
        src = (HERE / d["file"]).read_text()
        rec = stepper.record(src, d.get("stdin", ""))
        assert rec["status"] == "ok", (d["id"], rec["status"], rec["error"])
        tutor = explain.explain_program(src)
        out.append({"id": d["id"], "title": d["title"], "keywords": d.get("keywords", []), "source": src,
                    "stdin": d.get("stdin", ""), "step_notes": d.get("step_notes", {}), "lines": tutor["lines"],
                    "refs": tutor["refs"], "recording": rec})
    return json.dumps(out, ensure_ascii=False, separators=(",", ":")) + "\n"


if __name__ == "__main__":
    ASSET.write_text(build())
    print("wrote", ASSET)
