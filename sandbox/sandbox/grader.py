"""Runs one program against a list of tests, each in its own fresh sandbox."""
from __future__ import annotations

from . import runner

MAX_TESTS = 20


def same_output(got: str, want: str) -> bool:
    """Trailing spaces on a line and trailing blank lines don't count; everything else does."""
    def norm(s: str) -> list[str]:
        lines = [l.rstrip() for l in s.replace("\r\n", "\n").split("\n")]
        while lines and lines[-1] == "":
            lines.pop()
        return lines
    return norm(got) == norm(want)


def grade(code: str, tests: list[dict], limits: runner.Limits = runner.Limits()) -> dict:
    if not 1 <= len(tests) <= MAX_TESTS:
        raise ValueError("between 1 and 20 tests")
    results = []
    for t in tests:
        r = runner.run(code, t.get("stdin", ""), limits)
        results.append({"passed": r.status == "ok" and same_output(r.stdout, t["expect"]), "status": r.status,
                        "stdout": r.stdout, "error": r.error, "ms": r.duration_ms})
    return {"passed": sum(r["passed"] for r in results), "total": len(results), "tests": results}
