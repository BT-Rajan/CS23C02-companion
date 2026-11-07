import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "stepper"), str(ROOT / "tutor")]
import live  # noqa: E402


def test_run_records_and_explains():
    out = json.loads(live.run("x = 1\nprint(x)\n"))
    assert out["recording"]["status"] == "ok" and out["recording"]["stdout"] == "1\n"
    assert [l["n"] for l in out["lines"]] == [1, 2]
    assert set(out["refs"]) == {"CON-0001", "CON-0003"}


def test_syntax_error_still_answers():
    out = json.loads(live.run("for i in range(3)\n    print(i)\n"))
    assert out["recording"]["status"] == "syntax_error" and out["lines"] == [] and out["refs"] == {}


def test_input_round_trip():
    src = "n = int(input('n? '))\nprint(n * 2)\n"
    assert json.loads(live.run(src))["recording"]["status"] == "needs_input"
    assert json.loads(live.run(src, "21"))["recording"]["stdout"] == "n? 21\n42\n"


def test_live_run_without_echo_matches_what_the_sandbox_would_see():
    out = json.loads(live.run("n = int(input())\nprint(n * 2)\n", "4", echo=False))
    assert out["recording"]["stdout"] == "8\n"
