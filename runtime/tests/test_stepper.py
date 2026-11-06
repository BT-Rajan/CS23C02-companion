import json
import pathlib
import subprocess
import sys
import textwrap

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "stepper"))
import stepper  # noqa: E402


def rec(src, **kw):
    return stepper.record(textwrap.dedent(src), **kw)


def top_vars(step):
    return {k: v["r"] for k, v in step["stack"][-1]["vars"].items()}


def test_loop_records_every_line_with_state_before_it_runs():
    r = rec("""
        total = 0
        for i in range(3):
            total = total + i
        print("total is", total)
    """)
    assert r["status"] == "ok" and r["stdout"] == "total is 3\n"
    lines = [s["line"] for s in r["steps"] if s["event"] == "line"]
    assert lines == [2, 3, 4, 3, 4, 3, 4, 3, 5]
    last_line = [s for s in r["steps"] if s["event"] == "line"][-1]
    assert top_vars(last_line) == {"total": "3", "i": "2"}
    assert r["steps"][-1]["event"] == "return" and r["steps"][-1]["out"] == len(r["stdout"])


def test_changed_names_what_the_previous_line_set():
    r = rec("a = 1\nb = 2\na = 1\na = 5\n")
    changed = [s["changed"] for s in r["steps"]]
    assert changed == [[], ["a"], ["b"], [], ["a"]]


def test_function_call_shows_stack_locals_and_return_value():
    r = rec("""
        def square(n):
            result = n * n
            return result
        x = square(4)
    """)
    inside = [s for s in r["steps"] if len(s["stack"]) == 2]
    assert inside and inside[0]["stack"][1]["name"] == "square"
    assert top_vars(inside[-1]) == {"n": "4", "result": "16"}
    ret = next(s for s in r["steps"] if s["event"] == "return" and len(s["stack"]) == 2)
    assert ret["value"] == {"r": "16", "t": "int"}
    assert top_vars(r["steps"][-1])["square"] == "function square"
    assert top_vars(r["steps"][-1])["x"] == "16"


def test_recursion_gives_each_call_its_own_frame():
    r = rec("""
        def fact(n):
            if n <= 1:
                return 1
            return n * fact(n - 1)
        print(fact(4))
    """)
    assert r["stdout"] == "24\n"
    deepest = max(r["steps"], key=lambda s: len(s["stack"]))
    assert [f["name"] for f in deepest["stack"]] == ["main program"] + ["fact"] * 4
    ids = [f["id"] for f in deepest["stack"][1:]]
    assert len(set(ids)) == 4
    assert [f["vars"]["n"]["r"] for f in deepest["stack"][1:]] == ["4", "3", "2", "1"]


def test_infinite_loop_stops_at_step_limit():
    r = rec("while True:\n    x = 1\n", max_steps=100)
    assert r["status"] == "step_limit" and len(r["steps"]) == 100


def test_program_cannot_swallow_the_stop():
    r = rec("""
        while True:
            try:
                x = 1
            except:
                pass
    """, max_steps=50)
    assert r["status"] == "step_limit"


def test_slow_program_stops_at_time_limit():
    r = rec("while True:\n    x = sum(range(200000))\n", max_seconds=0.2)
    assert r["status"] == "time_limit"


def test_uncaught_error_is_a_result_with_its_line():
    r = rec("a = 10\nb = 0\nc = a / b\n")
    assert r["status"] == "error"
    assert r["error"] == {"type": "ZeroDivisionError", "message": "division by zero", "line": 3}
    assert r["steps"][-1]["event"] in ("exception", "return")
    assert any(s["event"] == "exception" and s["exc"]["type"] == "ZeroDivisionError" for s in r["steps"])


def test_caught_error_is_shown_but_program_finishes():
    r = rec("try:\n    int('x')\nexcept ValueError:\n    print('not a number')\n")
    assert r["status"] == "ok" and r["stdout"] == "not a number\n"
    assert any(s["event"] == "exception" for s in r["steps"])


def test_syntax_error_reports_line():
    r = rec("x = 1\nif x > 0\n    print(x)\n")
    assert r["status"] == "syntax_error" and r["error"]["line"] == 2 and r["steps"] == []


def test_input_is_fed_and_echoed():
    r = rec("name = input('Name? ')\nprint('Hi', name)\n", stdin="Asha")
    assert r["status"] == "ok" and r["stdout"] == "Name? Asha\nHi Asha\n"


def test_missing_input_pauses_and_rerun_replays_the_same_prefix():
    src = "import random\nn = random.randint(1, 100)\na = input('a? ')\nb = input('b? ')\nprint(n, a, b)\n"
    first = rec(src, stdin="1")
    assert first["status"] == "needs_input" and first["stdout"].endswith("b? ")
    second = rec(src, stdin="1\n2")
    assert second["status"] == "ok"
    assert second["steps"][:len(first["steps"])] == first["steps"]


def test_runs_are_deterministic_including_random():
    src = "import random\nxs = [random.random() for _ in range(5)]\nprint(xs)\n"
    assert rec(src) == rec(src)
    assert rec(src, seed=1)["stdout"] != rec(src, seed=2)["stdout"]


def test_recorder_restores_global_state():
    import random
    before = random.getstate()
    rec("import random\nrandom.random()\n")
    assert random.getstate() == before
    tracer = sys.gettrace()
    rec("x = 1\n")
    assert sys.gettrace() is tracer
    assert all(sys.monitoring.get_tool(t) != "cs23c02-stepper" for t in range(6))


def test_bare_except_around_input_cannot_hang():
    # The classic "keep asking" loop: with no input left it must pause, not spin.
    r = rec("""
        while True:
            try:
                n = int(input('Number? '))
                break
            except:
                print('Try again')
    """, stdin="abc")
    assert r["status"] == "needs_input" and r["stdout"] == "Number? abc\nTry again\nNumber? "


def test_library_disable_does_not_leak_into_the_next_run():
    rec("import json\njson.dumps([1])\n")
    r = rec("x = 1\ny = 2\n")
    assert [s["line"] for s in r["steps"] if s["event"] == "line"] == [1, 2]


def test_import_allow_list():
    assert rec("import math\nx = math.sqrt(16)\n")["status"] == "ok"
    assert rec("from collections import Counter\nc = Counter('aab')\n")["status"] == "ok"
    r = rec("import os\n")
    assert r["status"] == "error" and r["error"]["type"] == "ImportError" and "not available" in r["error"]["message"]
    assert rec("import collections.abc\n")["status"] == "ok"
    assert rec("from . import x\n")["status"] == "error"


def test_dangerous_builtins_are_absent():
    for src in ("open('/etc/passwd')\n", "eval('1')\n", "exec('x=1')\n", "breakpoint()\n"):
        r = rec(src)
        assert r["status"] == "error" and r["error"]["type"] == "NameError", src


def test_output_is_capped():
    r = rec("while True:\n    print('x' * 1000)\n")
    assert r["status"] in ("output_limit", "step_limit")
    assert len(r["stdout"]) <= stepper.MAX_OUTPUT


def test_values_are_capped_and_broken_repr_is_safe():
    r = rec("""
        class Bad:
            def __repr__(self):
                raise RuntimeError('boom')
        big = list(range(1000))
        s = 'y' * 5000
        b = Bad()
    """)
    v = top_vars(r["steps"][-1])
    assert len(v["big"]) <= stepper.REPR_LIMIT and "..." in v["big"]
    assert len(v["s"]) <= stepper.REPR_LIMIT
    assert v["b"] == "<Bad (cannot display)>"
    assert v["Bad"] == "class Bad"


def test_default_object_repr_has_no_memory_address():
    src = "class P:\n    pass\np = P()\n"
    v = top_vars(rec(src)["steps"][-1])
    assert "0x" not in v["p"] and v["p"].startswith("<__main__.P object")
    assert rec(src) == rec(src)


def test_library_code_is_never_stepped_into():
    r = rec("import json\nt = json.dumps({'a': [1, 2]})\n")
    assert all(f["name"] == "main program" for s in r["steps"] for f in s["stack"])


def test_modules_and_dunders_are_hidden():
    v = top_vars(rec("import math\nx = 1\n")["steps"][-1])
    assert v == {"x": "1"}


def test_recording_is_json_serialisable():
    r = rec("def f(x):\n    return [x, {'k': (1, 2)}]\ny = f(3)\n")
    assert json.loads(json.dumps(r)) == r


def test_lambda_and_generator_frames_have_readable_names():
    r = rec("sq = lambda n: n * n\nt = sum(sq(i) for i in range(3))\n")
    names = {f["name"] for s in r["steps"] for f in s["stack"]}
    assert {"lambda", "generator expression"} <= names


@pytest.mark.parametrize("src,code", [
    ("x = 1\nprint(x)\n", 0),
    ("x = 1 / 0\n", 1),
    ("import datetime, time\ntime.sleep(0.001)\nprint(datetime.datetime.now().microsecond)\n", 1),
])
def test_cli_check_for_the_book_build(tmp_path, src, code):
    f = tmp_path / "example.py"
    f.write_text(src)
    p = subprocess.run([sys.executable, str(ROOT / "stepper" / "stepper.py"), str(f)], capture_output=True, text=True)
    assert p.returncode == code, p.stderr


def test_committed_demo_recordings_match_the_recorder():
    sys.path.insert(0, str(ROOT / "demos"))
    import build_demos
    assert build_demos.ASSET.read_text() == build_demos.build(), "run python runtime/demos/build_demos.py"


def test_input_echo_can_be_turned_off_for_grading():
    src = "n = int(input())\nprint(n * 2)\n"
    assert rec(src, stdin="4")["stdout"] == "4\n8\n"                      # stepper: reads like a terminal
    assert rec(src, stdin="4", echo_input=False)["stdout"] == "8\n"        # grading/Run: exactly what was printed
