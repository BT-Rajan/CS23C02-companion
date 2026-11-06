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
| The runner's CSP refuses `eval` of strings (found on GitHub CI: an older Playwright's `waitForFunction` uses `eval` in the page and was blocked; the test now polls via the DevTools protocol) | CI run 42220544047 log |
**Not evidenced:** the WebView itself (shouldInterceptRequest for worker requests, blockNetworkLoads) on a real Android device,
memory use, and speed on the Redmi. The Chromium test emulates the asset loader and the network block.

# Roadmap A2 — reader, content and progress on the phone
| Area | Evidence |
|---|---|
| Genuine package verifies; one changed manifest byte, another key, garbage signature, missing key → refused (C3) | `PackageVerifierTest` |
| Signed but wrong format / too-new `min_app_version` / unsafe or duplicate paths → refused | `PackageVerifierTest` |
| Each file must match size and SHA-256 | `PackageVerifierTest.fileMustMatchItsHashAndSize` |
| Manifest altered on disk → package refused on next open; lesson file altered on disk → never shown | `ContentStoreTest` |
| Failed or wrongly signed update leaves the installed package untouched; nothing downloaded on an unverified manifest | `ContentStoreTest`, `ReaderRepositoryTest` |
| Offline: installed lessons keep working | `ReaderRepositoryTest.offlineKeepsWhatIsInstalled`, `ReaderFlowTest` |
| Progress events deduped locally, sent in batches, kept when offline, server history merged by union; resume rule identical to the server's | `ProgressStoreTest` |
| Read → Done → next lesson → Continue; app restart remembers lesson and scroll position | `ReaderFlowTest` |
| **Real backend:** signed fixture package imported and published; the app client downloads, verifies and reads it; progress accepted, replay = duplicate, unknown lesson reported, resume correct, restored after a "reinstall" | `LiveBackendContractTest` via `android/scripts/contract-test.sh` (mutation-checked) |
**Not evidenced:** encryption of the offline copy (ADR architecture §6 offline key expiry): lessons are in app-private, no-backup
storage and wiped on sign-out, but not encrypted (K26). Behaviour on a real phone.
**Found and fixed during A2:** the A1 rule "release builds need two certificate pins" had never been applied (an edit that did not
match the file); it is now in place together with "release builds need CONTENT_SIGNING_PUBKEY", and both were checked to fail a release build.

# Roadmap A5 — practice
| Area | Evidence |
|---|---|
| Right answer → verdict + explanation; wrong → verdict + why that option is wrong, answer withheld until the 2nd wrong try; per account | `backend/tests/test_practice.py` |
| Trace items checked the same way; their public file carries the code, never the answer | `test_practice.py`, `content-tools/tests/test_build.py` |
| Assessment items get the same 404 as unknown ids (D1) | `test_assessment_items_are_indistinguishable_from_unknown_ones` (mutation: removing the purpose check fails it) |
| No session → 401; no entitlement / other edition → 403; unpublished release → 404 | `test_practice.py` |
| Tampering: unknown option, client-supplied `correct` or `user_id`, injection-shaped option, wrong id shapes → 422/404 | `test_tampered_requests_are_rejected` |
| 31st try at one question within an hour → 429 (D7) | `test_hammering_one_question_is_rate_limited` |
| Manifest + every public file contain no answers or explanations | `test_no_endpoint_hands_out_keys_before_an_attempt` |
| Reveal policy enforced by the server (mutation: always revealing fails two tests) | mutation run, A5 |
| App sends only the choice (and edition); practice never changes progress | `PracticeTest`, live contract test |
| **Real backend:** wrong → why only; 2nd wrong → answer + explanation; trace correct; assessment item 404; progress unchanged | `LiveBackendContractTest` |
| Sample trace questions: the right option equals what the recorder prints | `android/scripts/make-sample-content.py` |
**Not built:** anomaly alerts for unusual practice patterns (D7), per-option analytics, offline practice (by design, ADR 0009).

# Roadmap A6 — exercises and the server sandbox
| Area | Evidence |
|---|---|
| E1 infinite loop (CPU limit), sleeping forever (wall clock) | `sandbox/tests/test_runner.py` |
| E2 memory bomb → `memory` | same |
| E3 output flood capped at 64 KB | same |
| E4 host files invisible (`/etc`, `/home`, `/root`, `/var`); `/usr` and the code read-only; scratch space ≤ 4 MB; PID 1 is the sandbox's own reaper with an empty environment | same |
| E5 no network, no DNS | same |
| E6 fork bomb contained (≤ 32 processes) and **does not starve a parallel job** (per-job UIDs; an earlier version failed this under parallel load and was redesigned) | same |
| E7 dangerous imports refused — and the OS layers hold even when the allow-list is bypassed on purpose | same |
| E8 runs as UID 65534 inside, sees only its own processes, no environment | same |
| Signed API↔sandbox: unsigned, wrongly signed, stale, tampered and replayed requests refused; forged answers refused by the API | `sandbox/tests/test_service.py`, `backend/tests/test_exercises.py` |
| Hidden tests never returned (their inputs and outputs are absent from every response) | `test_hidden_tests_never_reveal_their_data` (mutation: leaking them fails 2 tests) |
| Hints/solution exactly per approved policy #3 (mutation: unlocking one check early fails) | `test_hints_and_solution_follow_the_approved_policy` |
| One check at a time per student; 20/h per exercise; sandbox down → clear 503, not counted as a failure | `test_exercises.py` |
| Production refuses `SANDBOX_MODE=local` and weak sandbox keys | `test_policy.py` |
| Reference solutions pass all their tests in the sandbox, else the build fails (K13) | `content-tools/tests/test_build.py` |
| **Real services:** app client → backend → sandbox service over signed HTTP: wrong/right/runaway submissions, hint release, solution after pass, pass in progress | `LiveBackendContractTest` |
| On-device Run/grading prints exactly what the program printed (input echo off) — found while testing: the stepper's terminal-style echo would have marked correct sample answers wrong | `ExerciseTest`, runner test |
**Not evidenced:** seccomp (not applied), sandbox under load, a separate sandbox host and mutual TLS (deployment, A8), third-party review.

# Roadmap A7 — unit assessments
| Area | Evidence |
|---|---|
| Server chooses and orders questions; options shuffled per attempt; stable within an attempt (D6) | `backend/tests/test_assessments.py` |
| Nothing before submit reveals correctness: start, view and saves contain no answers or explanations (D1) | same |
| Client cannot claim a score or answer outside its attempt (D3) | same |
| Submit is idempotent; replay with other answers returns the stored result; saving after submit refused; stale autosaves (lower seq) refused (D4) | same, and live contract |
| One attempt per 24 h after the last one, enforced on the server (D5; mutation: removing the rule fails 2 tests) | same |
| **8 simultaneous Start requests create exactly one attempt** — found two real bugs while testing: a MySQL named lock released on a different pooled connection after commit, then a REPEATABLE READ snapshot hiding a concurrent attempt; fixed with a row lock and a locking read (mutation: removing the lock fails the test 3/3) | `test_simultaneous_starts_create_exactly_one_attempt` |
| 3-hour expiry: saved answers are marked automatically | `test_an_expired_attempt_is_scored_from_its_saved_answers` |
| Another student's attempt id behaves exactly like a missing one (C2) | `test_c2_…` |
| Assessment questions never checkable as practice; builder refuses practice items in unit assessments and assessment items reused in practice | `test_assessment_items_are_never_checkable_as_practice`, `content-tools/tests/test_build.py` |
| Exercise checks now use a transaction row lock (NOWAIT) instead of a named lock, so a crash can never leave a student locked out | `test_one_check_at_a_time_per_student` |
| App: intro/attempt/result/review, autosave with seq, idempotent submit, Me screen; debug sample engine follows the same rules; practice checker cannot see assessment keys | `AssessmentTest` |
| **Real backend:** start/resume, stale save refused, submit 3/3, idempotent resubmit, cooldown, history, best score in progress | `LiveBackendContractTest` |
**Not built:** lesson quizzes (`LSN.quiz` → `lesson_quiz`), timed assessments beyond the 3-hour session, anomaly re-verification for account sharing (K9).

# Roadmap A8 — staging stack
`deploy/local-test.sh` (CI: `.github/workflows/deploy.yml`) builds the images, starts the whole stack with fresh random secrets
and checks, over real HTTPS through Caddy:
| Area | Evidence |
|---|---|
| Sandbox container starts as root and isolates each job with its own UID (`"isolation": "per-job uid"`) | local-test |
| Sandbox container reaches neither the internet nor MySQL (internal network holding only api + sandbox) | local-test |
| Student code inside the sandbox has no network (socket probe submitted as an exercise) | `smoke.py` |
| Landing page served with CSP `default-src 'none'` and HSTS; template rendered; API docs not exposed (404); only `/v1/*` and `/healthz` proxied | `smoke.py` |
| Register → verification email → sign in → activation code from the CLI → signed manifest + file → exercise graded with hidden tests → assessment scored on the server → progress | `smoke.py` |
| Migrations run as the migrator; runtime user gets per-table grants only | `deploy/migrate.sh` (stack starts only if it succeeds) |
| Backup: consistent dump + content archive with checksums; **restore drill** loads it into a scratch MySQL with no network and matches schema version and row counts of 7 tables; content archive complete | `restore-drill.sh` |
| Database passwords never on a command line (`MYSQL_PWD`) | scripts |
**Not evidenced:** a real host, real DNS/Let's Encrypt, real Gmail delivery (K6), off-site backup copy (K40), load.

# K9 — anomaly re-verification (ADR 0004, attack B1)
| Area | Evidence |
|---|---|
| Ordinary use (3 devices, one network) never triggers | `test_ordinary_use_on_three_devices_never_triggers` |
| Four networks in a day trigger: every live session gets 403 `reverify_required` on progress and content; `/v1/me` says so; sign-out and sign-in still work (never a lock) | `test_many_addresses_…`, `test_content_is_gated_too` |
| Code goes to the account's own address (hint only on screen); wrong code refused; confirmation is per session; a code works once | `test_each_session_confirms_…` |
| Six sign-ins from one address (hostel NAT) and four device-cap evictions in a week also trigger; the audit names the signal | `test_many_signins_…` |
| No re-trigger while the window is open; devices first seen after it are free; unconfirmed old sessions still must confirm | `test_no_retrigger_…` |
| A device confirmed after one trigger must confirm again after a later one | `test_a_confirmed_device_…` |
| Three triggers in 30 days raise one support flag; CLI lists and clears it with a note | `test_repeated_triggers_…` |
| Endpoints need a session; "not needed" sends nothing | `test_reverify_endpoints_…` |
| Mutation checks: removing the gate fails 1 test; ignoring the confirmation time fails 1; requiring every session forever fails 1 | manual, recorded here |
| App: sign-in that needs confirmation opens "Confirm it's you" with the hint; wrong code shows the server message; right code continues to Home; a mid-session 403 opens the screen instead of an error; Back does not bypass it | `AppViewModelTest` (2 tests), screenshot `06b-reverify` |
| **Real backend:** six sign-ins → `reverify_required`, content 403, code by email, wrong code refused, confirmed, then "not needed" | `LiveBackendContractTest.anomalyReverificationAgainstRealServer` |
**Not evidenced:** thresholds on real traffic (K43); alerting beyond the support flag list.
