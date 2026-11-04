# Known-issues register
| ID | Issue | Severity | Status |
|---|---|---|---|
| K1 | Zigora test suite, dependency CVE scan and secret scan not yet run | Info | Open |
| K2 | Textbook repo has an architecture draft but no chapters or items; several decisions (P1–P9) pending; app package schema unvalidated against real content | High (blocks real-content work in Phases 3–4) | Open |
| K3 | Pyodide performance on low-end devices unmeasured | Medium | Open (gate in Phase 5) |
| K4 | Printing/code-handoff process undefined | Medium | Open |
| K5 | Classic PAT with broad scopes shared in chat | High | Open — owner to rotate |
| K6 | Gmail SMTP path untested against a real account; consumer Gmail has daily send limits | Medium | Open (needs credentials; ADR 0011) |
| K7 | CI workflow written but not yet executed on GitHub | Low | Open |
| K8 | Argon2id parameters (64 MiB, t=3) not yet benchmarked on the production host | Low | Open |
| K9 | Anomaly re-verification (B1) not built; only the 3-device cap exists | Medium | Open (Phase 2 follow-up) |
| K10 | Rate limiter can overshoot slightly under heavy concurrency; DB-backed (no Redis) | Low | Accepted |
| K11 | `rate_events` needs the daily `purge-rate-events` cron job | Low | Open (ops) |
