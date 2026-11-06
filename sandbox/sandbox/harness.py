"""Runs inside the sandbox: executes /code/main.py as the student's program with the ADR 0006 import allow-list.
The allow-list is defence in depth only; the real boundary is the bubblewrap container and resource limits around it."""
import builtins
import sys

ALLOWED = {"math", "random", "statistics", "string", "datetime", "collections", "itertools", "functools", "re", "json"}
FILENAME = "<student>"
_real_import = builtins.__import__


def _guarded(name, globals=None, locals=None, fromlist=(), level=0):
    caller = sys._getframe(1).f_code.co_filename
    if caller == FILENAME and (level != 0 or name.split(".")[0] not in ALLOWED):
        raise ImportError(f"The module '{name}' is not available in this course.")
    return _real_import(name, globals, locals, fromlist, level)


def main():
    with open("/code/main.py", encoding="utf-8") as f:
        source = f.read()
    code = compile(source, FILENAME, "exec")
    builtins.__import__ = _guarded
    # Running the student's program is this file's purpose; it executes inside the bubblewrap container.
    exec(code, {"__name__": "__main__", "__builtins__": builtins})  # nosec B102


if __name__ == "__main__":
    main()
