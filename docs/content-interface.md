# Content interface: textbook repo → app
**Authority:** `BT-Rajan/CS23C02` (`docs/architecture/decision-register.md` and `architecture-spec-draft.md`, commit 33de0d3). This
document does not redefine the book's structure. It records (1) what the app consumes, (2) the **App Content Package**, which is
the app side's responsibility, and (3) requests to the textbook side. Items marked *pending* in the textbook register (P1–P9) are
treated as unresolved: nothing here depends on them except where stated.
It replaces the earlier `content-contract.md`, which was written before the textbook architecture was visible and is withdrawn.

## 1. What the app relies on (approved textbook decisions)
| Textbook decision | How the app uses it |
|---|---|
| D4/R7 permanent type-prefixed IDs, never reused | Stable keys for progress, bookmarks, attempts and content versions (`TOP-0142`, `ITM-0210`, `EXR-0031`, `ASM-0101`, `LSN-0087`) |
| D5/U1/R2 ordering in per-parent manifests | App navigation is built from manifests, never from IDs or filenames |
| R1/S2 lessons are `LSN` objects in `app/lessons.manifest`, delivering one topic or contiguous subtopics, never crossing topics | One app lesson = one `LSN`. Topics without a lesson are not shown (or shown as "in the book") |
| D7/R4 central bank of `ITM` (questions) and `EXR` (programming exercises); `ASM` assessments reference them | One question bank, no copy in the app. ITM `purpose: practice | assessment` drives which mode may use an item |
| R6 five MCQs per digital lesson (configurable) | `LSN.quiz` → an `ASM` with purpose `lesson_quiz` |
| D11 solutions policy (MCQ immediate feedback in app; assessment answers after completion; exercise hints/solutions in app; challenge problems hints only) | Matches ADR 0009; thresholds for hint/solution release are app policy (see open questions) |
| A5 execution metadata (Python version, stdin, expected stdout/exit, determinism, timeout) | Same fields drive the sandbox runner and its tests; `python: "3.12"` pins our runtime |
| S9/A2 Draft and Release validation profiles | The app consumes **Release builds only** |
| D3/D6/S10 levels 1–4 | Shown sparingly (never as pressure); used for ordering practice |
| R5/§17 print selection lives in print manifests | Ignored by the app |

## 2. App Content Package (v0 — defined here, because the textbook spec lists the "digital extraction output" schema as not yet defined)
The textbook build (their item 11/13) emits **two outputs** from a Release build:
1. **Public package** (signed, versioned, downloadable): `manifest.json` (book id `BOK-…`, edition, content version, min app version, file hashes, Ed25519 signature), navigation tree from manifests, one `lessons/LSN-xxxx.json` per lesson (rendered semantic blocks with IDs and typed references; figures and code assets by hash), `items/ITM-xxxx.json` with **stem, option IDs and text, level, purpose** and no key, `exercises/EXR-xxxx.json` with statement, io_spec, constraints, starter code and **public** tests, `assessments/ASM-xxxx.json` (ordered ITM/EXR IDs and rules).
2. **Protected set** (never in any package; imported only into the server database): for ITM the answer and explanations; for EXR hidden tests, hints, `solution_approach`, `reference_solution`; for ASM nothing beyond rules.
A CI check fails the build if any protected field name or value appears in the public package (attack C3/D2).
Content versions are immutable; a new version never reuses an ID for different meaning (consistent with D4). Progress is keyed by ID, so retired IDs map via the registry's `successor`.

## 3. Requests to the textbook side — **accepted 2026-11-06** (book repo decision X3)
1. **Public vs hidden tests.** `EXR.tests` is one list of `{stdin, expect}`. The app needs `visibility: public | hidden` per test (students see a few examples; the rest grade). Proposed: add the field, default `hidden`.
2. **Interactive tracing data.** `ITM.type: trace` exists, but stepping data (statements executed, variable state per step) is not defined. Proposed: derive it at build time by running the `COD` under a tracer, so it can never disagree with the code.
3. **Per-option explanations** on MCQ items (the brief asks for explanations of wrong options). Proposed optional `option_explanations`.
4. **Edition and content version fields** on the book manifest (`edition`, `version`, `min_app_version`) so the package manifest can be generated, and a mapping from `BOK-0001`+edition to the commercial edition the activation code unlocks.
5. **Non-MCQ item types** (`short_answer`, `classification`, `conceptual`): the app cannot auto-grade free text. Proposed: the app shows the model answer after the student commits an attempt (self-check), and records no score. Needs your decision.
6. **Companion app location:** their spec says it is "not yet located". It is `BT-Rajan/CS23C02-companion`.
7. **Dependencies on pending decisions:** extraction output depends on P3/P4 (authoring syntax and reader) and P8 (release scope). The package schema above is independent of both, which is why it sits on the app side.

## 4. Consequences for the app plan
- Phase 3 can start against **fixture content** that follows the approved structures (IDs, manifests, ITM/EXR/ASM/LSN), stored under `content-tools/fixtures/` in this repo and clearly marked as non-book content. No book content exists yet.
- The package builder (`content-tools/`) and the leakage check are built here against the fixtures; the textbook build adopts the same schema later.

## 5. Additions for the tutor (ADR 0014; book decision X4)
- **Program entries:** every steppable `COD` used as a worked example gets a stable `title` and optional `keywords` (words students
  might use to ask for it) and optional `step_notes`.
- **Construct registry export:** `registry/constructs.yaml` (spec §8) exported with each `CON`'s introducing topic, unit and section.
- **Page map:** the print build emits `{object ID → printed page}` so the app can say "page 74". Never authored by hand (A6).
- The app package gains, per program: `lines` (explanation, constructs, shape) and `refs`, generated by `runtime/tutor/explain.py`.

## 6. Practice items (A5)
`ITM` items of type `mcq` or `trace` with `purpose: practice` are checked by `POST /v1/practice/items/{id}/answer`. A trace item
carries `code` (the program it asks about) in the public package; its answer stays in the protected set. The build should verify
that the correct option of a trace item is exactly what the program prints (known issue K30).
