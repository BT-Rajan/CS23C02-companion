# Roadmap: finishing the book and the companion app
Written 2026-11-06 from the state of both repositories:
- `BT-Rajan/CS23C02` (book): architecture only (decision register + draft spec, commit 33de0d3). **No chapters, no syllabus, no tooling.**
- `BT-Rajan/CS23C02-companion` (app): backend (auth, activation, signed content delivery, progress) **tested**; content builder **tested on fixtures**; Android = hello-world + proven CI loop.

**The critical path is the book's content, not the app.** The app can be built end-to-end on fixtures, but it cannot ship
without real Release-validated content, and the content cannot be written without the syllabus. So the plan runs two tracks
in parallel and joins them early with one pilot unit.

## 0. Decisions (updated 2026-11-06)
**Accepted by the owner:** textbook P1–P9, companion open questions #1–#5 and #7, ADRs 0001–0009, Pandoc → LaTeX, and a new
headline feature: **line-by-line stepping of real code** (ADR 0013; book decision X2).
Still needed from the owner:
| # | Needed | Why it blocks |
|---|---|---|
| U1 | **Official CS23C02 syllabus** (PDF/text) and the regulation it belongs to | Units, outcomes, coverage report, publication gate |
| U2 | Hosting budget/provider, domain, Play developer account (open questions #6, #8) | Staging (A8) and release (A9) |
| U3 | Rotate the shared PAT (K5); install debug builds on the reference phone (K17) | Security; only a real phone can judge feel and speed |

## Track B — Book (`BT-Rajan/CS23C02`)
| Phase | Deliverable | Done when |
|---|---|---|
| B1 Foundations | `syllabus/syllabus.yaml`, conventions doc (D10), unit/outcome plan (5 units provisional), construct registry draft | Every syllabus item has one home topic (U7) on paper |
| B2 Tooling | ID registry tool (R7/S8), validator with Draft/Release profiles + waivers (S9, A2), pinned Pandoc `commonmark_x` reader (P4), code runner (A5, spec §18.8) **plus the X2 stepper check on every executable block and a check that each trace item's answer equals the recorded output**, **digital extraction → App Content Package v0** (spec §18.11, same schema as `content-tools/`), validator fixture suite incl. broken files | Tool tests green in CI; extraction output passes the companion `leakcheck` |
| B3 **Pilot unit** (Unit 1, fully) | Topics, worked examples with `step_notes`, code, ITM (5/lesson, R6), EXR with public/hidden tests, unit assessment, LSN registrations | Passes Release; imported by the companion backend; readable in the app (joins Track A3) |
| B4 Units 2–5 | Same as pilot, one unit at a time, each through Release | Coverage report shows taught/reinforced/assessed for every syllabus item |
| B5 Print | Pandoc → LaTeX print filters, 6×9 template, selection manifests, answer key, index, proofs | Full PDF compiles; page-budget report reviewed |
| B6 Book release | Editorial review, waivers listed, syllabus verified, ISBN/printer files, activation-code handoff (open question #5) | Printer-ready PDF + signed content package for the same edition |

## Track A — App (`BT-Rajan/CS23C02-companion`)
Each slice: backend + Android + tests, built on fixtures until B3 lands. **The stepper (A3) is the app's centre**, so the
on-device Python runtime and its performance gate come before practice items.
| Phase | Deliverable | Done when |
|---|---|---|
| A1 Android shell | Compose app: welcome, register, verify, sign in/reset, activate (screens 1–5), encrypted token storage, silent refresh, cert pinning hook | Owner signs up and activates a test code on the reference phone |
| A2 Reader | Home with Continue, Unit, Topic reader (screens 6–8), package download + signature check, offline reading, progress events | Fixture lessons read offline; resume works across reinstall |
| A3 **Line-by-line stepper + tutor** (ADR 0013, 0014) | `runtime/stepper` harness (tested on CPython), Pyodide bundled in a locked-down WebView, Step / Step back / Play / slider, variables + call stack + output, stdin box, `step_notes`; **tutor: ask for a program → Show me / I'll type it**, per-line explanations, hints (purpose → shape → line), typo/indent checks, book references on every line; Run and live tutoring for free-form code | Performance gate on the Redmi (first step ≤ 3 s after warm-up, ≤ 150 MB); harness tests incl. infinite loop, recursion, exceptions, `input()` |
| A4 Real content | Import the B3 pilot package; build-time recordings in `traces/`; fix schema gaps (K2, K12) | Pilot unit readable and steppable end-to-end |
| A5 Practice | MCQ check endpoint (verdict + explanation per question), trace questions (predict → commit → step to see why) | Attack C3/D2 tests pass; stepper locked until prediction is committed |
| A6 Exercises & graded runs | Exercise editor (step your own code), server sandbox for Check with hidden tests, hints/solutions policy, reference solutions run in the build (K13) | Sandbox escape/limit suite passes |
| A7 Assessments | Server state machine (ADR 0009), screen 12, Me screen 13, anomaly re-verification (K9) | Replay/tamper/score tests pass |
| A8 Staging | Host + region, domain, SPF/DKIM, real Gmail test (K6), landing page (ADR 0008), backups | Owner uses staging for a full unit |
| A9 Hardening & release | Restore drill, full attack-register re-test, usability test, key rotation (K14), Play listing + release build | Play internal → closed → production |

## Order of work
1. **A1 Android shell** — done (CI green).
2. **A3 stepper + tutor** — recorder, stepper, tutor and **Python on the phone** (own code, live explanations, input) done; **waiting on the owner's Redmi numbers** (performance gate); A2 (reader), A5 (practice), A6 (exercises + server sandbox) and A7 (unit assessments + Me screen) done; A8 staging stack built and tested end to end locally and in CI (`deploy/`); **deploying it needs U2** (host, domain, Gmail). Next: anomaly re-verification (K9), or A4 when the book has chapters.
3. **B2 tooling** in the book repo (registry, validator, code runner + stepper check from X2, extraction to App Content Package v0).
4. **B1/B3** as soon as the syllabus (U1) arrives; then A4 → A7 alongside book writing (the long pole).

## Rules carried forward
No secrets in Git · migrations for every schema change · tests with every feature · placeholders are never called features ·
nothing marked *pending* is treated as approved · no syllabus-compliance claim before the syllabus is verified.
