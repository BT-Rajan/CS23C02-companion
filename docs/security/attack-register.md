# Attack-scenario register
"Actual result" is **Not tested** everywhere until the relevant phase delivers evidence. Cells are filled in by test reports, not by assumption.

| ID | Attacker attempts | Target | Expected protection | Planned test | Actual result | Residual |
|---|---|---|---|---|---|---|
| A1 | Reuse redeemed code | Activation | Atomic state change; generic failure | Integration: redeem twice | Pass (Phase 2) – see test-evidence.md | None |
| A2 | Share code before redemption | Commercial | Inherent; batch revocation; support recovery | Process review | Not tested | **Medium** |
| A3 | Guess codes | Activation | 75-bit codes; per-account/IP limits; alerts | Rate-limit + statistical test | Controls pass (Phase 2) – see test-evidence.md | Very low |
| A4 | Automated redemption | Activation | Verified-email requirement; limits; backoff | Load script | Not tested | Low |
| A5 | 100 parallel redemptions of one code | Activation | Exactly one success | Concurrency test (threads/processes) | Pass (Phase 2) – see test-evidence.md | None |
| A6 | Tamper activation request (edition/user) | Activation | Edition comes from the code row; user from token | Fuzz params | Pass (Phase 2) – see test-evidence.md | None |
| A7 | Replay successful request | Activation | Idempotent; second returns generic failure | Replay test | Pass (Phase 2) – see test-evidence.md | None |
| A8 | Many accounts, one code | Activation | Unique constraints on code and (user, edition) | Concurrency + DB constraint test | Pass (Phase 2) – see test-evidence.md | None |
| A9 | Fake client "activated" flag | Client | Flags are cosmetic; every API call checks server entitlement | Patch app; call APIs | Server-authoritative (Phase 2) – see test-evidence.md | None |
| B1 | Credential sharing | Accounts | Device cap, anomaly re-verification, support flag | Multi-device simulation | Partial: device cap only – see test-evidence.md | **Medium** |
| B2 | Brute-force login | Auth | Throttling, Argon2id, uniform errors | Burst test | Pass (Phase 2) – see test-evidence.md | Low |
| B3 | Account enumeration (login, register, forgot) | Auth | Uniform responses and timing | Differential test | Pass (Phase 2) – see test-evidence.md | Low |
| B4 | Abuse reset codes | Recovery | 6-digit, 10 min, 5 tries, single use, send caps | Attempt test | Pass (Phase 2) – see test-evidence.md | Low |
| B5 | Steal/replay refresh token | Sessions | Rotation + reuse detection revokes family | Replay test | Pass (Phase 2) – see test-evidence.md | Low |
| B6 | Tamper access token | Auth | Signature + expiry + algorithm pinning | `alg=none`, bad-sig tests | Pass (Phase 2) – see test-evidence.md | None |
| B7 | Skip entitlement check | API | Central dependency on every protected route | Route inventory test: every route declares policy | Pass (Phase 2) – see test-evidence.md | None |
| B8 | Recovery via support social engineering | Process | Proof-of-purchase procedure | Process review | Not tested | Medium |
| C1 | Fetch content without activation | Content | Entitlement on manifest and files | Negative tests | Pass (Phase 3 backend) – see test-evidence.md | None |
| C2 | Change edition/object IDs (BOLA) | Content/progress | Object's edition checked against entitlement; own-user only | Authorization matrix tests | Pass (Phase 3 backend) – see test-evidence.md | None |
| C3 | Extract keys from APK/cache | Content | Keys/hidden tests never in packages | Package scan in CI | Pass for build, importer and served files (Phase 3) – see test-evidence.md | None |
| C4 | Flip local entitlement flag | Client | No effect on server data; offline key expires | Patch + offline test | Not tested | Low |
| C5 | Read cache after logout/user switch | Client | Encrypted store keyed per account; wiped on logout | Device test | Backend revocation passes; device cache not built – see test-evidence.md | Low |
| C6 | Bulk scrape lessons | Content | Volume/rate detection; signed short-lived URLs | Load + alert test | Partial: per-account brake only – see test-evidence.md | **Medium** |
| C7 | Screenshot/photograph content | Content | None possible | – | Accepted | **High (accepted)** |
| D1 | Read answers from API/pre-submit | Assessment | No correctness in pre-submit payloads; the practice endpoint refuses assessment items with the same 404 as unknown ones | Response schema test | Partial (A5): practice endpoint cannot be used for assessment items – `test_practice.py`; assessment API not built | None |
| D2 | Inspect app resources for keys | Assessment | None shipped | CI package scan | Pass for packages (Phase 3); release APK carries no keys (only the debug build has answers for its SAMPLE questions, outside the signed package) – see test-evidence.md | None |
| D3 | Submit altered scores/answers | Assessment | Server scores from raw choices | Tamper tests | Not tested | None |
| D4 | Replay a submission | Assessment | Idempotent request id | Replay test | Not tested | None |
| D5 | Unlimited retries via local reset | Assessment | Attempt counts server-side | Reset test | Not tested | None |
| D6 | Share answers by option position | Assessment | Per-attempt shuffle | Statistical test | Not tested | Low |
| D7 | Repeated practice calls to learn keys | Practice | One question per call; answer revealed only after a right answer or 2 wrong tries; 300/h per account, 30/h per question; anomaly alerts not built | Enumeration test | Pass for limits and reveal policy (A5) – `test_practice.py` (mutation-checked) | **Medium** (inherent to immediate feedback; practice keys are not assessment keys) |
| D8 | Forge progress events | Progress | Only low-stakes events accepted from client; verified events server-derived | Event fuzz | Partial pass (Phase 3) – see test-evidence.md | Low |
| E1 | Infinite loop | Sandbox | Wall/CPU limits, kill | Hostile suite | Not tested | None |
| E2 | Memory bomb | Sandbox | cgroup limit | Hostile suite | Not tested | None |
| E3 | Output flood | Sandbox/app | Output cap, truncation | Hostile suite | Not tested | None |
| E4 | File access/write | Sandbox | Read-only root, tmpfs, no secrets mounted | Hostile suite | Not tested | None |
| E5 | Network access | Sandbox | No network namespace | Hostile suite | Not tested | None |
| E6 | Fork bomb / subprocess | Sandbox | pids limit, seccomp | Hostile suite | Not tested | None |
| E7 | Malicious imports (`os`, `ctypes`, …) | Sandbox | Allow-list + isolation as defence in depth | Hostile suite | Not tested | None |
| E8 | Sandbox escape | Sandbox | gVisor/nsjail, non-root, no shared state, fresh per job, host patching | Third-party review before release | Not tested | **Low–Medium** |
| E9 | Flood Submit to exhaust workers | Sandbox | Queue, per-user concurrency 1, limits, circuit breaker | Load test | Not tested | Low |
| E10 | Tamper local run result to look passed | Practice | Local results never recorded as pass | Tamper test | Not tested | None |
| F1 | Unauthorised API calls | API | Auth dependency + route inventory test | ZAP/API suite | Not tested | None |
| F2 | SQL injection | API | ORM/parameterised queries only; lint ban on raw string SQL | SAST + sqlmap on staging | Not tested | None |
| F3 | XSS | Web/API | Landing page static; lesson rendering sanitises content; WebView JS disabled outside runner | Content XSS suite | Not tested | Low |
| F4 | Request flood | API | Edge rate limits, per-route limits | Load test | Not tested | Low |
| F5 | Malformed payloads | API | Schema validation, size caps | Fuzz | Pass (Phase 2) – see test-evidence.md | None |
| F6 | Secrets in logs/APK/repo | All | Secret manager; CI secret scanner; log scrubbing | Scan | Not tested | Low |
| F7 | DB privilege misuse | DB | Least-privilege roles; audit table append-only | Role test | Pass (Phase 2) – see test-evidence.md | None |
| F8 | Leak at print/distribution | Commercial | Batch states, revoke batch, secure code handoff | Process review | Not tested | **Medium** |
| F9 | MITM on public Wi-Fi | Network | TLS only, pinning, no cleartext | Proxy test | Not tested | Low |
