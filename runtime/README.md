# runtime/stepper — line-by-line recorder (ADR 0013)
`stepper/stepper.py` is one standard-library file that **really runs** a Python program and records it line by line. The same
file runs in the book build (CPython 3.12, book decision X2) and on the phone inside Pyodide (roadmap A3, slice 3), so a worked
example that passes the build is guaranteed to step the same way in the app.

```
python runtime/stepper/stepper.py example.py [--stdin "3\n4"] [--json]   # build check: exit 0 = steps cleanly and identically twice
```
```python
from stepper import record
rec = record(source, stdin="", max_steps=2000, max_seconds=2.0, seed=0)
```

## How it works
- Hooks are `sys.monitoring` (PEP 669, Python ≥ 3.12), **not** `sys.settrace`: when a settrace hook raises, CPython switches
  tracing off, so a program with a bare `except:` could swallow the stop and loop forever. A monitoring hook raises again on every
  line until the program ends (tested: `test_program_cannot_swallow_the_stop`, `test_bare_except_around_input_cannot_hang`).
- Only code compiled as `<student>` is recorded; library code is never stepped into.
- `random` is seeded (default 0) and memory addresses are removed from values, so two runs give identical recordings.
- `input()` reads the given lines and echoes them like a terminal. If input runs out the status is `needs_input`: the app asks for
  the next line and records again with it appended (same seed → same prefix, then it continues).
- Imports are limited to the ADR 0006 allow-list; `open`, `eval`, `exec`, `compile`, `breakpoint` are absent. This is a teaching
  convenience, **not** the security boundary: isolation comes from the Pyodide worker (phone) or the sandbox/subprocess around it.

## Recording format (version 1)
```jsonc
{
  "version": 1,
  "status": "ok | error | syntax_error | needs_input | step_limit | time_limit | output_limit | unavailable",
  "stdout": "full output text",
  "error": null | {"type": "ZeroDivisionError", "message": "division by zero", "line": 3, "column": 5 /* syntax only */},
  "steps": [{
    "event": "line | return | exception",   // line: state BEFORE this line runs; return: a function (or the program) ends
    "line": 3,
    "stack": [{"id": 1, "name": "main program | <function> | lambda | generator expression", "line": 3,
               "vars": {"total": {"r": "10", "t": "int"}}}],   // outermost first; r = display text (≤ 200 chars), t = type
    "changed": ["total"],                   // names in the innermost frame whose value the previous step changed
    "out": 11,                              // length of stdout at this step (slice stdout[:out] to show output so far)
    "value": {"r": "16", "t": "int"},       // return events of functions only
    "exc": {"type": "ValueError", "message": "..."}  // exception events only (raised here, may be caught later)
  }]
}
```
Limits: 2,000 line events, 2 s wall time, 64 KB output, values capped at 200 characters and 50 container elements.
Measured on the build machine: bubble sort of 6 numbers 54 steps in 2 ms (12 KB JSON); a 2,000-step recording 7.5 ms (300 KB).
Phone numbers come from the Pyodide performance gate (A3 slice 3).

## Tests
`python -m pytest runtime/tests` (28 tests): loops, functions, recursion, errors, syntax errors, input and re-run, determinism,
allow-list, missing dangerous builtins, output/step/time limits, programs that try to swallow the stop, value capping, JSON shape,
and the CLI check used by the book build.

# runtime/tutor — line explainer (ADR 0014)
`tutor/explain.py` turns a program into per-line tutor data: *what this line does* (plain English from the `ast`), the constructs it
uses (`CON` IDs), a *shape* hint (`for _ in _:`), and book references from the construct registry. `demos/build_demos.py` runs it
with the recorder and writes the app asset. `constructs.sample.json` is a **placeholder** registry (every entry `sample: true`)
until the book's `registry/constructs.yaml` and the print build's page map exist (book decision X4).
