# CS23C02 Companion

Companion app for the *CS23C02* undergraduate Python programming textbook.

> **Principle:** the best learning experience with the fewest necessary features.
> Problem-solving is the subject; Python is the tool.

**Current phase: Phase 1 – architecture (documents only, no application code yet).**
See [`docs/status.md`](docs/status.md) for what is implemented, tested, incomplete and proposed.

## Product rules (summary)
- One physical book → one student → one account → one activation (server is the authority).
- Learning journey: Read → Understand → Explore → Practise → Check → Improve.
- Three activities: MCQs (concepts), code tracing (execution reasoning), Python practice (problem solving).
- Practice mode teaches; assessment mode measures. Assessment rules are enforced by the backend.
- Platforms: Android app (Kotlin/Compose, minSdk 26) and a static web landing page (QR target). No student web client in release 1.

## Repository map
| Path | Purpose |
|---|---|
| `docs/zigora-audit.md` | Verified audit of the Zigora repo: reuse / rebuild / discard |
| `docs/adr/` | Architecture decision records |
| `docs/architecture.md` | Components, data flow, data model, API surface |
| `docs/content-contract.md` | Proposed contract between the textbook repo and the app |
| `docs/security/` | Threat model, attack register, control matrix, residual risks |
| `docs/ux/` | Journeys, screen map, design system |
| `docs/open-questions.md` | Decisions awaiting approval |
| `docs/known-issues.md` | Known-issues register |

## Source repositories
- Textbook (canonical content): `BT-Rajan/CS23C02` (private; empty at time of writing)
- Zigora (code quarry, not a base): `BT-Rajan/zigora`

## Working rules
No secrets in Git. Migrations for every schema change. Tests with every feature. A placeholder is never described as a feature.
