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

# Roadmap A1 — Android shell (screens 1–6)
Environment: JDK 21, Gradle 8.14.3, AGP 8.7.3, Android SDK 35 (local); backend on MySQL 8.0.46 for the contract test.
Result: **35 Android JVM tests** (34 run on every build, plus the live-backend contract test), lint clean apart from pinned-version notices.
| Area | Evidence |
|---|---|
| Activation-code check matches the server | `ActivationCodeTest`: server-generated codes accepted; every single-character typo and every adjacent swap rejected |
| Client password/email checks mirror the server | `ValidationTest`; the contract test confirms the server's `weak_password` on the same input |
| Error shape, rate-limit `Retry-After`, non-JSON 5xx never shown raw, offline | `ApiClientTest` |
| Refresh: proactive on expiry, once on 401, **single-flight under 5 parallel calls** (no false refresh-reuse revocation, B5), wiped on failure | `ApiClientTest` |
| Activation request carries only the code (A6) | `ApiClientTest.activateSendsOnlyTheCode` |
| Journey J1 (create → verify → activate → home), unverified login, wrong password, reset, offline start, sign-out | `AppViewModelTest` (10 tests) |
| **App ↔ real backend contract** (paths, fields, status codes, rotation, logout, reset) | `LiveBackendContractTest` via `android/scripts/contract-test.sh`: passed against a real FastAPI + MySQL backend |
| Screens render in light, dark, 200 % font on 360×640 dp | `ScreenshotTest` (`-Pscreenshots`), images in the CI `screens` artifact |
**Not evidenced:** behaviour on a real phone (K17), Keystore token encryption on a device (JVM tests use the in-memory store),
certificate pinning against a real certificate (no staging host yet; F9), TalkBack walkthrough.

# Roadmap A3 — line-by-line stepper, slices 1–2
| Area | Evidence |
|---|---|
| Real execution recorded per line: stack, variables, changed names, output offsets, return values, exceptions | `runtime/tests/test_stepper.py` (29 tests, 3.12 and 3.13) |
| Runaway programs: infinite loop → `step_limit`; slow loop → `time_limit`; print flood → `output_limit` (E1, E3 at recorder level) | same |
| **A program cannot swallow the stop** (bare `except:`; "keep asking" input loop) — found during testing: a `sys.settrace` hook is switched off by CPython when it raises, so the recorder was rebuilt on `sys.monitoring` | `test_program_cannot_swallow_the_stop`, `test_bare_except_around_input_cannot_hang` |
| Imports limited to the ADR 0006 list; `open/eval/exec/compile/breakpoint` absent (E7, convenience only) | `test_import_allow_list`, `test_dangerous_builtins_are_absent` |
| Determinism (seeded `random`, memory addresses stripped) so the book build can reject nondeterministic examples (X2) | `test_runs_are_deterministic_including_random`, CLI tests |
| Committed demo recordings always equal the recorder's output | `test_committed_demo_recordings_match_the_recorder` |
| App parses the harness output; forward/back exact; narration, notes, frames, endings | `StepperStateTest` (10), `AppViewModelTest.stepperOpensFromHomeAndBackReturns` |
**Not evidenced:** recording on the phone (Pyodide), speed on the Redmi, TalkBack walkthrough of the stepper. The recorder is not a
security boundary; worker/sandbox isolation (E1–E8) is tested in slice 3 and A6.

## A3 — tutor (ADR 0014)
| Area | Evidence |
|---|---|
| Per-line explanations, constructs, shape hints and book references derived from the code | `runtime/tests/test_explain.py` (8) |
| Guided typing: prefix = still typing; spacing/quote style accepted; indentation, missing colon, `=` vs `==`, unbalanced brackets/quotes, case, small typos, different lines, extra lines, completion; blank lines/comments ignored; tabs = 4 spaces | `GuidedTypingTest` (14) |
| Asking in the student's own words ("Write a program to find factorial") finds the right program; unknown topics find none | `ProgramSearchTest` (4) |
| Auto-indent after `:` and inside blocks; pastes untouched | `AutoIndentTest` (6) |
**Limits:** book references are from `runtime/tutor/constructs.sample.json` (marked "sample reference" in the app) until the book's
construct registry and page map exist (book decision X4). The tutor checks are practice only and record nothing on the server.

## A3 slice 3 — Python on the phone
| Area | Evidence |
|---|---|
| Recorder runs unchanged in Pyodide 0.27.8 (CPython 3.12.7), incl. the bare-`except` stop | Node probe; `android/runner-test/runner-test.cjs` |
| **Phone recording is identical to the build-time recording** (so what the book build verified is what students see) | runner test |
| Only APK assets are served; no request leaves the app; no CSP violations (F3, F6-adjacent) | runner test (all other hosts refused, count checked) |
| Python cannot import `js` or `pyodide` (no route to the browser API from student code) (E7) | runner test |
| C-level hang (`10 ** (10 ** 7)`) is killed by the watchdog and the runner recovers (E1) | runner test |
| In-page fallback gives the same recording | runner test (`?mode=page`) |
| Live run → stepper; input → continue from the pause; syntax errors as results; friendly messages | `LiveRunTest` (6, real CPython via `CPythonRunner`) |
| Checksum pin on the downloaded runtime (supply chain) | `runnerAssets` task fails on mismatch |
**Not evidenced:** the WebView itself (shouldInterceptRequest for worker requests, blockNetworkLoads) on a real Android device,
memory use, and speed on the Redmi. The Chromium test emulates the asset loader and the network block.
