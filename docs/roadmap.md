# Roadmap: finishing the book and the companion app
Written 2026-11-06 from the state of both repositories:
- `BT-Rajan/CS23C02` (book): architecture only (decision register + draft spec, commit 33de0d3). **No chapters, no syllabus, no tooling.**
- `BT-Rajan/CS23C02-companion` (app): backend (auth, activation, signed content delivery, progress) **tested**; content builder **tested on fixtures**; Android = hello-world + proven CI loop.

**The critical path is the book's content, not the app.** The app can be built end-to-end on fixtures, but it cannot ship
without real Release-validated content, and the content cannot be written without the syllabus. So the plan runs two tracks
in parallel and joins them early with one pilot unit.

## 0. Unblock (owner, this week)
| # | Needed | Why it blocks |
|---|---|---|
| U1 | **Official CS23C02 syllabus** (PDF/text) and the regulation it belongs to | Units, outcomes (D12, U7), coverage report, publication gate (P8) |
| U2 | Decide textbook P1–P9 (recommend: accept every recommendation in the register as written) | Authoring syntax (P3/P4) and validator severity (P5/P6) must be fixed before writing chapters |
| U3 | Approve companion open questions #1–#5 (recommend: accept defaults) | Hidden tests, trace data, assessment and hint policy |
| U4 | Typesetting choice: **Pandoc → LaTeX** (recommended; Pandoc is already pinned in the spec) vs Typst | Print template and filters |
| U5 | Rotate the shared PAT (K5); install the hello-world APK on the reference phone (K17) | Security; confirms the device loop |

## Track B — Book (`BT-Rajan/CS23C02`)
| Phase | Deliverable | Done when |
|---|---|---|
| B1 Foundations | `syllabus/syllabus.yaml`, conventions doc (D10), unit/outcome plan (5 units provisional), construct registry draft | Every syllabus item has one home topic (U7) on paper |
| B2 Tooling | ID registry tool (R7/S8), validator with Draft/Release profiles + waivers (S9, A2), pinned Pandoc reader, code runner (A5, spec §18.8), **digital extraction → App Content Package v0** (spec §18.11, same schema as `content-tools/`), validator fixture suite incl. broken files | Tool tests green in CI; extraction output passes the companion `leakcheck` |
| B3 **Pilot unit** (Unit 1, fully) | Topics, worked examples, code, ITM (5/lesson, R6), EXR with public/hidden tests, unit assessment, LSN registrations | Passes Release; imported by the companion backend; readable in the app (joins Track A3) |
| B4 Units 2–5 | Same as pilot, one unit at a time, each through Release | Coverage report shows taught/reinforced/assessed for every syllabus item |
| B5 Print | Print filters, 6×9 template, selection manifests, answer key, index, proofs | Full PDF compiles; page-budget report reviewed |
| B6 Book release | Editorial review, waivers listed, syllabus verified, ISBN/printer files, activation-code handoff (open question #5) | Printer-ready PDF + signed content package for the same edition |

## Track A — App (`BT-Rajan/CS23C02-companion`)
Each slice: backend + Android + tests, built on fixtures until B3 lands.
| Phase | Deliverable | Done when |
|---|---|---|
| A1 Android shell | Compose app: welcome, register, verify, sign in/reset, activate (screens 1–5), secure token storage, cert pinning | Owner signs up and activates a test code on the reference phone |
| A2 Reader | Home with Continue, Unit, Topic reader (screens 6–8), package download + signature check, offline reading, progress events | Fixture lessons read offline; resume works across reinstall |
| A3 **Real content** | Import the B3 pilot package; fix schema gaps found (K2, K12) | Pilot unit readable end-to-end |
| A4 Practice | MCQ check endpoint (verdict + explanation per question, rate-limited), code tracing with build-time trace data (screens 9–10) | Attack C3/D2 tests pass; no key reachable before an attempt |
| A5 Execution | On-device Pyodide Run (ADR 0006), server sandbox for Check (gVisor/nsjail), hints/solutions policy, reference solutions run in the build (K13) | **Performance gate** on reference phone; sandbox escape/limit suite passes |
| A6 Assessments | Server state machine (ADR 0009), screen 12, Me screen 13, anomaly re-verification (K9) | Replay/tamper/score tests pass |
| A7 Staging | Host + region (open question #6), domain, SPF/DKIM, real Gmail test (K6), landing page (ADR 0008), backups | Staging used by the owner for a full unit |
| A8 Hardening & release | Restore drill, security re-test of the whole attack register, usability test against journey targets, key-rotation procedure (K14), Play listing + release build | Play internal → closed → production track |

## Order of work (what I will do next, unless redirected)
1. **A1 Android shell** — needs nothing from the owner except U5 testing on the phone.
2. **B2 tooling** in the book repo, starting with the registry tool and extraction to App Content Package v0 — needs U2 (P3/P4 at least).
3. **B1/B3** the moment the syllabus (U1) arrives: plan units, then write the pilot unit.
4. A2 → A4 while B3/B4 content is being written and reviewed.

Book writing (B3–B4) is the long pole: roughly one unit per review cycle. App phases A4–A6 fit inside that window.

## Rules carried forward
No secrets in Git · migrations for every schema change · tests with every feature · placeholders are never called features ·
nothing marked *pending* is treated as approved · no syllabus-compliance claim before the syllabus is verified.
