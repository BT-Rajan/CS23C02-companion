# Status register
Legend: **Proposed** (designed, not built) · **Incomplete** · **Implemented** · **Tested** (with evidence).

| Item | State | Evidence |
|---|---|---|
| Zigora audit (read-only portions) | Implemented | `docs/zigora-audit.md` |
| Zigora test run, dependency CVE scan, secret scan | Incomplete | – |
| ADRs 0001–0009 | Proposed | `docs/adr/` |
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
| Android app screens | Not started (hello-world only; **not yet installed on a real phone**) | – |
| Android build environment | Decided: GitHub Actions (ADR 0012); workflow **not yet written or run** | – |
