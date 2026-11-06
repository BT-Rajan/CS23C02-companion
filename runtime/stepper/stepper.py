"""Line-by-line recorder for student and book code (ADR 0013, book decision X2).

One file, standard library only, so the SAME code runs in the book build (CPython 3.12) and on the phone (Pyodide).
It really runs the program under sys.monitoring (PEP 669) and records, before each line runs: the line number, the call stack with
each frame's variables, and how much output exists. The app replays the recording, so stepping back is exact.

    record(source, stdin="") -> dict (JSON-serialisable; schema in runtime/README.md)

Limits stop runaway programs with a clear status instead of hanging: max_steps line events and max_seconds of wall
time. A program that catches every exception still cannot swallow the stop: the hook keeps raising on every line.
This is a teaching tool, not a security boundary: isolation comes from the Pyodide worker / server sandbox around it.
"""
from __future__ import annotations

import builtins
import io
import random
import re
import reprlib
import sys
import time
import types

_mon = sys.monitoring
_TOOL_CANDIDATES = (_mon.DEBUGGER_ID, 3, 4)

VERSION = 1
FILENAME = "<student>"
DEFAULT_MAX_STEPS = 2000
DEFAULT_MAX_SECONDS = 2.0
MAX_OUTPUT = 64 * 1024
REPR_LIMIT = 200
# ADR 0006 allow-list; extended per lesson via content.
ALLOWED_MODULES = frozenset({"math", "random", "statistics", "string", "datetime", "collections", "itertools",
                             "functools", "re", "json"})


class _Stop(BaseException):
    """Raised inside the student's program to end it. BaseException so `except Exception` cannot catch it."""

    def __init__(self, status: str):
        super().__init__(status)
        self.status = status


class _SafeRepr(reprlib.Repr):
    def repr_instance(self, obj, level):
        try:
            return repr(obj)
        except Exception:
            return f"<{type(obj).__name__} (cannot display)>"


_ADDRESS = re.compile(r" at 0x[0-9a-fA-F]+")
_repr = _SafeRepr()
_repr.maxlist = _repr.maxtuple = _repr.maxset = _repr.maxfrozenset = _repr.maxdeque = _repr.maxarray = 50
_repr.maxdict = 50
_repr.maxstring = REPR_LIMIT
_repr.maxother = REPR_LIMIT
_repr.maxlevel = 4


def _show(value) -> dict:
    """{"r": short repr, "t": type name}. A broken __repr__ in student code must never crash the recorder."""
    if isinstance(value, types.FunctionType):
        return {"r": f"function {value.__name__}", "t": "function"}
    if isinstance(value, type):
        return {"r": f"class {value.__name__}", "t": "class"}
    try:
        r = _repr.repr(value)
    except BaseException as e:  # noqa: BLE001 - includes _Stop raised by tracing inside a student __repr__
        if isinstance(e, _Stop):
            raise
        r = f"<{type(value).__name__} (cannot display)>"
    r = _ADDRESS.sub("", r)  # memory addresses differ between runs and mean nothing to a student
    if len(r) > REPR_LIMIT:
        r = r[:REPR_LIMIT - 1] + "…"
    return {"r": r, "t": type(value).__name__}


def _visible(name: str, value) -> bool:
    return not (name.startswith("__") or isinstance(value, types.ModuleType))


_FRAME_NAMES = {"<module>": "main program", "<lambda>": "lambda", "<genexpr>": "generator expression"}


class _Output(io.StringIO):
    def write(self, s: str) -> int:
        room = MAX_OUTPUT - self.tell()
        if room <= 0:
            raise _Stop("output_limit")
        return super().write(s[:room])


class _Recorder:
    def __init__(self, stdin_lines: list[str], max_steps: int, max_seconds: float):
        self.steps: list[dict] = []
        self.stdin = list(stdin_lines)
        self.max_steps, self.max_seconds = max_steps, max_seconds
        self.deadline = 0.0
        self.out = _Output()
        self.frame_ids: dict = {}
        self.next_id = 1
        self.last_vars: dict[int, dict[str, str]] = {}
        self.stopping: str | None = None

    # ---- monitoring callbacks (PEP 669) --------------------------------------------------------------------------
    # sys.monitoring, not sys.settrace: when a settrace hook raises, CPython switches tracing off, so a program with a bare
    # `except:` could catch the stop and then loop forever untraced. A monitoring callback can raise on every line.
    # sys._getframe(1) inside a callback is the student's frame being executed.
    def on_start(self, code, offset):
        if code.co_filename != FILENAME:
            return _mon.DISABLE  # never step into library code
        self.frame_ids[sys._getframe(1)] = self.next_id
        self.next_id += 1

    def on_line(self, code, line):
        if code.co_filename != FILENAME:
            return _mon.DISABLE
        self._check()
        if len(self.steps) >= self.max_steps:
            self.stopping = "step_limit"
            raise _Stop("step_limit")
        self._snapshot(sys._getframe(1), "line", None)

    def on_return(self, code, offset, value):
        if code.co_filename != FILENAME:
            return _mon.DISABLE
        frame = sys._getframe(1)
        self._check()
        self._snapshot(frame, "return", value)
        self._forget(frame)

    def on_raise(self, code, offset, exc):
        # Non-local event: DISABLE is not allowed here, and we never raise from it.
        if code.co_filename != FILENAME or isinstance(exc, _Stop) or self.stopping:
            return
        self._snapshot(sys._getframe(1), "exception", exc)

    def on_unwind(self, code, offset, exc):
        if code.co_filename == FILENAME:
            self._forget(sys._getframe(1))

    def _check(self):
        if self.stopping:
            raise _Stop(self.stopping)
        if time.monotonic() > self.deadline:  # checked per event: a single slow builtin call can overrun
            self.stopping = "time_limit"
            raise _Stop("time_limit")

    def _forget(self, frame):
        fid = self.frame_ids.pop(frame, None)
        self.last_vars.pop(fid, None)

    def _snapshot(self, frame, event, arg):
        stack, f = [], frame
        while f is not None:
            if f.f_code.co_filename == FILENAME:
                stack.append(f)
            f = f.f_back
        stack.reverse()
        frames, changed = [], []
        for f in stack:
            fid = self.frame_ids.setdefault(f, 0)
            scope = f.f_globals if f.f_code.co_name == "<module>" else f.f_locals
            vars_ = {k: _show(v) for k, v in list(scope.items()) if _visible(k, v)}
            frames.append({"id": fid, "name": _FRAME_NAMES.get(f.f_code.co_name, f.f_code.co_name),
                           "line": f.f_lineno, "vars": vars_})
            if f is frame:
                before = self.last_vars.get(fid, {})
                changed = [k for k, v in vars_.items() if before.get(k) != v["r"]]
                self.last_vars[fid] = {k: v["r"] for k, v in vars_.items()}
        step = {"event": event, "line": frame.f_lineno, "stack": frames, "changed": changed, "out": self.out.tell()}
        if event == "return" and frame.f_code.co_name != "<module>":
            step["value"] = _show(arg)
        if event == "exception":
            step["exc"] = {"type": type(arg).__name__, "message": str(arg)[:REPR_LIMIT]}
        self.steps.append(step)

    # ---- student-facing builtins ---------------------------------------------------------------------------------
    def input(self, prompt=""):
        self.out.write(str(prompt))
        if not self.stdin:
            self.stopping = "needs_input"
            raise _Stop("needs_input")
        line = self.stdin.pop(0)
        self.out.write(line + "\n")  # echo like a terminal, so the output pane reads naturally
        return line

    def print(self, *args, sep=" ", end="\n", file=None, flush=False):
        builtins.print(*args, sep=sep, end=end, file=self.out if file is None else file, flush=flush)

    @staticmethod
    def importer(name, globals=None, locals=None, fromlist=(), level=0):
        if level != 0 or name.split(".")[0] not in ALLOWED_MODULES:
            raise ImportError(f"The module '{name}' is not available in this course.")
        return __import__(name, globals, locals, fromlist, level)


def record(source: str, stdin: str = "", *, max_steps: int = DEFAULT_MAX_STEPS,
           max_seconds: float = DEFAULT_MAX_SECONDS, seed: int = 0) -> dict:
    """Run `source` and return the recording. stdin is newline-separated input lines. `seed` makes random repeatable,
    so re-running with one more input line (status needs_input) replays the same program up to that point."""
    result = {"version": VERSION, "status": "ok", "steps": [], "stdout": "", "error": None}
    try:
        code = compile(source, FILENAME, "exec")
    except SyntaxError as e:
        result.update(status="syntax_error", error={"type": "SyntaxError", "message": e.msg, "line": e.lineno,
                                                       "column": e.offset})
        return result

    rec = _Recorder(stdin.splitlines() if stdin else [], max_steps, max_seconds)
    safe_builtins = dict(vars(builtins))
    safe_builtins.update(input=rec.input, print=rec.print, __import__=rec.importer)
    for name in ("open", "exec", "eval", "compile", "breakpoint", "exit", "quit", "help"):
        safe_builtins.pop(name, None)
    env = {"__name__": "__main__", "__builtins__": safe_builtins}

    tool = next((t for t in _TOOL_CANDIDATES if _mon.get_tool(t) is None), None)
    if tool is None:
        result.update(status="unavailable", error={"type": "RuntimeError", "message": "The stepper is busy.", "line": None})
        return result
    E = _mon.events
    _mon.use_tool_id(tool, "cs23c02-stepper")
    for event, fn in ((E.PY_START, rec.on_start), (E.LINE, rec.on_line), (E.PY_RETURN, rec.on_return),
                      (E.RAISE, rec.on_raise), (E.PY_UNWIND, rec.on_unwind)):
        _mon.register_callback(tool, event, fn)
    state = random.getstate()
    random.seed(seed)
    rec.deadline = time.monotonic() + max_seconds
    try:
        _mon.set_events(tool, E.PY_START | E.LINE | E.PY_RETURN | E.RAISE | E.PY_UNWIND)
        exec(code, env)  # noqa: S102 - this IS the code runner; isolation is provided around it (ADR 0006)
    except _Stop as s:
        result["status"] = s.status
    except BaseException as e:  # noqa: BLE001 - the student's uncaught error is a result, not a crash
        tb, line = e.__traceback__, None
        while tb is not None:
            if tb.tb_frame.f_code.co_filename == FILENAME:
                line = tb.tb_lineno
            tb = tb.tb_next
        result.update(status="error", error={"type": type(e).__name__, "message": str(e)[:REPR_LIMIT], "line": line})
    finally:
        _mon.set_events(tool, 0)
        _mon.free_tool_id(tool)  # also unregisters callbacks and re-enables DISABLEd locations for the next run
        random.setstate(state)
    result["steps"] = rec.steps
    result["stdout"] = rec.out.getvalue()
    return result


def _main(argv: list[str]) -> int:
    """Book build check (X2): `python stepper.py FILE [--stdin TEXT] [--json]`. Exit 0 only if the program steps
    cleanly and identically twice (determinism)."""
    import argparse
    import json
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("file")
    p.add_argument("--stdin", default="")
    p.add_argument("--json", action="store_true", help="print the recording")
    a = p.parse_args(argv)
    with open(a.file, encoding="utf-8") as f:
        src = f.read()
    first, second = record(src, a.stdin), record(src, a.stdin)
    if a.json:
        print(json.dumps(first, ensure_ascii=False))
    problem = None
    if first["status"] != "ok":
        problem = f"status {first['status']}: {first['error']}"
    elif first["steps"] != second["steps"] or first["stdout"] != second["stdout"]:
        problem = "nondeterministic: two runs recorded different steps"
    if problem:
        print(f"{a.file}: {problem}", file=sys.stderr)
        return 1
    if not a.json:
        print(f"{a.file}: ok, {len(first['steps'])} steps")
    return 0


if __name__ == "__main__":
    sys.exit(_main(sys.argv[1:]))
