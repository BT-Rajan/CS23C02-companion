# 0013 Line-by-line code stepper (real execution, on device)
**Status.** Approved by owner (2026-11-06). Extends ADR 0006 (execution) and ADR 0009 (tracing integrity).

**Context.** The owner's central teaching requirement: a student learns code **line by line, in real time**: they press Step and
see the real program advance one line, with variables, the call stack and printed output updating. Static animations or
hand-written traces are not enough: they can disagree with the code and do not work for the student's own code.

**Decision.**
1. **Real execution, recorded then replayed.** The code runs in the on-device CPython (Pyodide, ADR 0006) under a Python
   `sys.settrace` harness. Each `line`, `call`, `return` and `exception` event records: line number, frame stack, local and global
   variables (`repr` capped at 200 characters, containers capped at 50 elements), and stdout so far. The UI replays the recording,
   so **Step back** is exact and free, and **Play** advances at a chosen speed (0.5–4 lines/s).
2. **One harness, two runtimes.** The same `stepper.py` runs on the phone (Pyodide) and in the book build (CPython 3.12). The build
   steps every executable, deterministic code block (book decision X2), so a worked example that the app can step is proven
   at Release time. The harness lives in `runtime/stepper/` with its own tests and is bundled into the app as an asset.
3. **Limits.** At most 2,000 line events and 2 s wall time per recording (watchdog from ADR 0006); beyond that the recording stops
   with a plain message ("This program ran more than 2,000 steps. Step through a smaller input."). `input()` reads from a stdin box
   shown before running; if it runs out, the student is asked for the next line and the recording continues.
4. **Where it appears.** A **Step through** button on every worked example and runnable code block in a lesson, and on the
   exercise editor for the student's own code. Optional author `step_notes` (book X2) appear under the code when their line is reached.
5. **Integrity.** Stepping is practice only and never changes progress or scores. On a **trace** question (predict the output), the
   stepper is locked until the student commits a prediction (ADR 0009); in assessments it is unavailable until the attempt is submitted.
   Recordings never leave the phone.
6. **Low-end fallback.** If the Pyodide performance gate fails on the reference phone, worked examples use the build-time recording
   shipped in the content package (same format), and stepping the student's own code uses the server sandbox (rate-limited, feature-flagged).

**Screen (one primary action).** Code with the current line highlighted and the previous line faintly marked; under it, a Variables
panel (changed values flash once, honouring reduce-motion), a Call stack row when inside a function, and the Output pane. Controls:
**Step**, Step back, Play/Pause, Restart, and a step slider. TalkBack announces "Line 4: total becomes 10".

**Consequences.** The Pyodide runtime and its performance gate move earlier in the roadmap (A3, before practice items).
The content package gains an optional `traces/` folder for build-time recordings. Trace questions get their stepping data from the
same harness, so book decision X3's "trace data derived at build time" is satisfied by this ADR.
