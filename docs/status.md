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
| GitHub Actions CI | Written, **not yet run** | – |
| Anomaly re-verification for credential sharing | Proposed (not built) | – |
| Content packages: builder, signing, leak check, fixtures | Implemented, Tested (12 tests) | `content-tools/` |
| Content delivery API (signed manifest, entitlement-gated files), importer, progress events + resume | Implemented, Tested (17 tests + mutation checks), backend only | `docs/security/test-evidence.md` |
| Assessments, execution | Not started | – |
| Android app | Not started (build via GitHub Actions, ADR 0012; workflow not yet written) | – |
| Android build environment | Decided: GitHub Actions (ADR 0012); workflow **not yet written or run** | – |
