# Security control matrix
| Control | Threat IDs | Layer | Phase |
|---|---|---|---|
| Atomic code redemption + unique constraints | A1 A5 A7 A8 | DB/API | 2 |
| HMAC-peppered code storage, secret manager | A3 F6 F8 | API | 2 |
| Rate limiting (edge + per-account + per-route) | A3 A4 B2 B4 D7 F4 | API/edge | 2 |
| Verified-email precondition for activation | A4 A8 | API | 2 |
| Uniform auth/recovery responses and timing | B3 | API | 2 |
| Argon2id + login throttling | B2 | API | 2 |
| Rotating refresh tokens + reuse detection | B5 | API | 2 |
| Device cap + anomaly re-verification | B1 | API | 2 |
| Central entitlement dependency + route-policy inventory test | B7 C1 C2 F1 | API | 2 |
| Object-level authorisation tests | C2 | API | 2–4 |
| Keys/hidden tests never in packages; CI package scan | C3 D2 | Build | 3–4 |
| Signed content manifests, hash-verified files | C1 | Build/App | 3 |
| Per-account encrypted offline store; key expiry | C4 C5 | App | 6 |
| Server-owned assessment state machine, per-attempt shuffle | D1 D3 D4 D5 D6 | API | 4 |
| Verified vs client-reported progress separation | D8 E10 | API | 4 |
| Execution sandbox, limits, allow-list, signed results | E1–E9 | Sandbox | 5 |
| WebView lockdown for local runner | E3 F3 | App | 5 |
| TLS only, pinning, no backup, R8, no exported components | C4 F9 | App | 2–3 |
| Least-privilege DB roles, append-only audit | F7 | DB | 2 |
| CI: SAST, dependency audit, secret scanning | F2 F6 | CI | 2 |
| Monitoring and alerts on abuse signals | A3 B1 C6 D7 E9 F8 | Ops | 8 |
