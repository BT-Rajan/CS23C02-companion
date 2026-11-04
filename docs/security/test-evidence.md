# Test evidence — Phase 2 (commercial access foundation)
Environment: MySQL 8.0.46, Python 3.12, schema built from the Alembic migration, runtime DB user with production-style grants.
Result of the last run: **Phase 2: 43 tests; Phase 3 slice 1: +17 backend tests = 60 backend tests, plus 12 content-tools tests, all passing** (Phase 2 concurrency tests repeated 12 times with no failure).
Static analysis: `bandit -ll` no findings. Dependencies: `pip-audit` no known vulnerabilities (at run time).
**Not evidenced yet:** CI on GitHub (workflow written, not yet run), real Gmail delivery, load testing, external penetration test, any content/assessment/sandbox controls (later phases).

## Attack scenarios exercised (IDs from attack-register.md)
| ID | Result | Test |
|---|---|---|
| A1 reuse | Pass: second use fails with the same generic message as unknown/unissued/revoked codes | `test_reused_unissued_revoked_and_unknown_codes_are_indistinguishable` |
| A3 guessing | Pass (controls): account lockout after 5 failures blocks even a real code; per-IP cap works. Entropy (75 bits) is by design, not tested statistically | `test_account_is_locked_out…`, `test_one_ip_cannot_guess…` |
| A5 simultaneous | Pass: 30 parallel redemptions ×3 rounds and 12 parallel HTTP requests → exactly one winner, one entitlement | `test_concurrent_redemption_*` |
| A6 tampered request | Pass: extra fields (edition/user) rejected; edition comes from the code row | `test_client_cannot_choose_edition_or_user` |
| A7 replay | Pass (same as A1) | – |
| A8 many accounts, one code | Pass: concurrency + DB unique constraints; racing two codes on one account keeps exactly one, loser's code stays unused | `test_same_account_racing_two_codes…` |
| A9 client-side flag | Server-side only: entitlement is read from the DB per request (no client flag exists to patch) | design + `/me` test |
| B2 login brute force | Pass: throttled with 429 even for the right password | `test_login_is_throttled…` |
| B3 enumeration | Pass: register, forgot and login return identical status/body for existing vs unknown emails | `test_register_does_not_reveal…`, `test_forgot_and_login_are_enumeration_safe` |
| B4 reset abuse | Pass: 5-attempt burn, single use, expiry, resend cooldown | `test_verify_code_burns…`, `test_codes_are_single_use…`, `test_new_code_invalidates…` |
| B5 token replay | Pass: reuse after grace revokes the session; grace window tolerates a lost response | `test_refresh_rotates…` |
| B6 token tampering | Pass: alg=none, wrong key, expired, wrong type, foreign user, unknown session, bit-flip, garbage all 401 | `test_tampered_forged…` |
| B7 skipped entitlement/auth | Pass: route inventory test fails if a route is neither on the public list nor behind the session dependency | `test_every_route_is_explicitly_public…` |
| F5 malformed payloads | Pass: 70 KB body → 413; validation errors never echo input | `test_request_hygiene` |
| F7 DB privilege misuse | Pass: runtime user cannot UPDATE/DELETE/TRUNCATE the audit log, DROP/ALTER tables or read alembic_version | `test_runtime_database_user…` |
| B1 sharing | Partial: device cap evicts the oldest session without blocking the newest. Anomaly re-verification is **not built yet** | `test_fourth_device…` |

## Findings (found by these tests or while building)
| # | Finding | Severity | Status |
|---|---|---|---|
| F-1 | `DateTime(6)` silently meant `timezone=6` and dropped microseconds in MySQL (would break ordering and the refresh-grace window) | Medium | Fixed (`DATETIME(fsp=6)`), migration regenerated |
| F-2 | Unverified login never sent the new code (FastAPI drops background tasks when a route raises) | Medium (functional; blocks sign-up recovery) | Fixed; covered by test |
| F-3 | Rate-limiter cleanup `DELETE` inside requests deadlocked under concurrency (would be HTTP 500s) | High under load | Fixed: cleanup moved to a scheduled CLI job |
| F-4 | MySQL cannot REVOKE a table privilege granted at database level; the planned audit-log restriction would have failed silently | High (security control) | Fixed: per-table grants generated from the models |
| F-5 | A per-IP failure cap of 20/day would lock out honest students behind a shared campus/hostel IP | Medium (UX) | Changed: default 100/day, configurable; the tight limit is per account |
| F-6 | Rate limiter is check-then-record, so heavy concurrency can overshoot a limit slightly | Low | Accepted; documented |


# Phase 3, slice 1 — content delivery and progress (backend only)
Not covered: any Android code (not started), real book content (none exists; tests use clearly-marked fixtures), offline encryption (Phase 6), the sandbox (Phase 5).
**Mutation checks** (each control deliberately broken, then restored; the tests failed every time): removing the edition-ownership filter on file downloads; skipping signature verification; accepting any requested edition; allowing arbitrary progress event kinds; accepting unknown content ids; skipping the importer leak check.

| ID | Result | Tests |
|---|---|---|
| C1 content without activation | Pass: login required (401); no entitlement (403) for manifest and files | `test_content_requires_login_and_activation` |
| C2 changing edition/object ids (BOLA) | Pass: unknown edition and someone else's edition give the identical 403; another edition's file hash and unknown/invalid hashes give the identical 404; progress is per account and a client-supplied user id is rejected | `test_unknown_edition_and_someone_elses_edition…`, `test_files_of_another_edition…`, `test_each_account_sees_only_its_own_progress…` |
| C3 keys in packages (build side) | Pass: public objects are built by allowlist; an unlisted source field cannot leak; an injected leak is caught by the independent leak check; Release validation rejects bad source | `content-tools/tests/test_build.py` |
| C3 / D2 keys in served files | Pass: every served file and the manifest were compared against all protected strings (15+ checked); importer re-checks and refuses even a validly signed package carrying a protected field | `test_nothing_protected_is_reachable…`, `test_importer_rejects_a_validly_signed…` |
| Package integrity | Pass: edited manifest, edited file, unlisted file, missing file, wrong signing key and path traversal are all refused; nothing is half-imported | `test_importer_rejects_tampering_in_every_form` |
| C5 revoked access | Pass: revoked entitlement stops manifest and file access (superseded files remain available to current owners so downloads survive an update; drafts are never served) | `test_drafts_are_not_served…` |
| D8 forged progress | Partial pass: only lesson_opened/lesson_completed are accepted from clients, content ids must exist in the account's current release, replays are idempotent (8 concurrent identical events → 1 accepted). Assessment and exercise outcomes are not client-writable (they do not exist yet) | `tests/test_progress.py` |
| C6 bulk scraping | Partial: 2000 file requests/hour/account brake is implemented; no volume-anomaly alerting yet and the limit itself is untested at its threshold | – |

Findings: none new from Phase 3 testing (first full run passed; the mutation checks above are the evidence that the tests are not vacuous). Two weak assertions in my own tests were found and tightened during review.
