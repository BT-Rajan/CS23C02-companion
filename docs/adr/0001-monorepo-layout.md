# 0001 Monorepo layout
**Context.** One small team, one product, tightly coupled API contract.
**Decision.** One repo: `backend/`, `android/`, `web-landing/`, `sandbox/` (execution service), `content-tools/` (validator/builder run in the textbook's CI), `docs/`. The textbook repo stays separate and canonical.
**Consequences.** Atomic changes across API and clients; one CI. The sandbox deploys separately despite living in the same repo.
