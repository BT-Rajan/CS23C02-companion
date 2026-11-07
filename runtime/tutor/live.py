"""One call for the phone (ADR 0013 slice 3, ADR 0014 §5): record the program and explain its lines.

    run(source, stdin="") -> JSON text {"recording": {...}, "lines": [...], "refs": {...}}

On the phone this runs inside Pyodide next to stepper.py, explain.py and the construct registry. Code that does not
parse still returns a recording (status syntax_error) and no line explanations.
"""
import json

import explain
import stepper

_REGISTRY = None


def run(source: str, stdin: str = "", echo: bool = True) -> str:
    global _REGISTRY
    if _REGISTRY is None:
        _REGISTRY = explain.load_registry()
    rec = stepper.record(source, stdin, echo_input=echo)
    try:
        tutor = explain.explain_program(source, _REGISTRY)
    except SyntaxError:
        tutor = {"lines": [], "refs": {}}
    return json.dumps({"recording": rec, **tutor}, ensure_ascii=False)
