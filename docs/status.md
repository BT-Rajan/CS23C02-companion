# Status register
Legend: **Proposed** (designed, not built) · **Incomplete** · **Implemented** · **Tested** (with evidence).

| Item | State | Evidence |
|---|---|---|
| Zigora audit (read-only portions) | Implemented | `docs/zigora-audit.md` |
| Zigora test run, dependency CVE scan, secret scan | Incomplete | – |
| ADRs 0001–0013 | Approved by owner (2026-11-06) | `docs/adr/` |
| Architecture, data model, API surface | Proposed | `docs/architecture.md` |
| Content interface (supersedes my earlier contract) | Proposed; textbook architecture draft is the authority | `docs/content-interface.md` |
| Threat model, attack register, control matrix | Proposed (no attack tested yet) | `docs/security/` |
| UX journeys, screen map, design system | Proposed | `docs/ux/` |
| Backend foundation: registration, verification, login, refresh rotation, device cap, password reset | Implemented, Tested (43 tests, real MySQL) | `docs/security/test-evidence.md` |
| Activation: code generation, atomic redemption, entitlements, audit, admin CLI | Implemented, Tested | same |
| Gmail SMTP delivery | Implemented, **not tested against real Gmail** | needs `.env` credentials |
| GitHub Actions CI (backend) | Implemented; first run green on GitHub (MySQL service, 60 tests, bandit, pip-audit) | run 41912969946 |
| Anomaly re-verification for credential sharing | Proposed (not built) | – |
| Content packages: builder, signing, leak check, fixtures | Implemented, Tested (12 tests) | `content-tools/` |
| Content delivery API (signed manifest, entitlement-gated files), importer, progress events + resume | Implemented, Tested (17 tests + mutation checks), backend only | `docs/security/test-evidence.md` |
| Assessments, execution | Not started | – |
| Android build loop (push → GitHub Actions → lint, unit test, debug APK → result read via API / ci-logs branch) | Implemented, **proven** with a hello-world app | run 41913367690 |
| Android shell (A1): welcome, create account, verify, sign in, forgot/reset, activate, A1 home; Keystore-encrypted tokens; single-flight refresh; OkHttp pinning (2 pins required for release); no backup/transfer | Implemented, Tested (35 JVM tests incl. live-backend contract); **not yet run on a real phone** | `docs/security/test-evidence.md` (A1) |
| Stepper slice 1: recorder harness `runtime/stepper` (sys.monitoring; limits; input; determinism; allow-list; book-build CLI check) | Implemented, Tested (29 tests, CPython 3.12 + 3.13; CI `runtime`) | `runtime/README.md` |
| Stepper slice 2: Android stepper screen (Step / Back / Play / speed / slider, current + previous line markers, narration, call stack, variables with changes, output, author notes) replaying **build-time recordings of 3 demo programs** | Implemented, Tested (10 replay-logic tests on the real asset, screenshots); **not yet on a real phone** | `docs/security/test-evidence.md` (A3) |
| Tutor (ADR 0014): ask for a program (offline search), **Show me** (stepper with "what this line does" + book references), **I'll type it** (live line-by-line checking: correct / spacing / indentation / typo / different, novice-mistake messages, hints purpose → shape → line, "type it for me", auto-indent, symbol row), line explainer `runtime/tutor/explain.py` | Implemented, Tested (8 explainer tests; 14 guided-typing, 4 search, 6 auto-indent tests; screenshots). Book references come from a **sample** construct registry until chapters exist; not yet on a real phone | `docs/security/test-evidence.md` (A3) |
| Stepper slice 3: live recording on the phone (Pyodide in a locked-down WebView), student's own code, `needs_input` prompt, performance gate | Not started (next) | – |
| Home/reader (A2) | Not started | – |
| Android build environment | GitHub Actions (ADR 0012), proven; screens rendered to a `screens` artifact on each run | run 41913367690 |
