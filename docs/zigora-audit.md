# Zigora audit (Phase 0)

Audited: shallow clone of `BT-Rajan/zigora` on 2026-11-04. Read: structure, auth, token handling, quiz/MCQ scoring, Android manifest/storage/build config, CI.
**Not done:** test suite not executed; dependencies not CVE-scanned; git history not scanned for secrets; most of the 9.5k-line `main.py` not read line by line. These are scheduled (see `docs/status.md`).

## Shape
FastAPI monolith (`backend/main.py`, 9,476 lines), React PWA (`App.jsx`, 3,682 lines), Kotlin/Compose Android (~7,800 lines), MariaDB, 85 backend test files. An LMS for colleges: teachers, parents/guardians, AI tutoring, Razorpay, Google sign-in, classes/calendars, three languages. Roughly 85% is out of scope.

## Findings
| Area | Finding | Consequence |
|---|---|---|
| Python execution | None. Only `subprocess` use is `git rev-parse`. "Sandbox" means sandboxed HTML iframes. | Build runner + sandbox from scratch. |
| Entitlement | Role/program-enrolment model; no book activation. | Rebuild. |
| Auth | PBKDF2-SHA256 600k (+upgrade), email-OTP registration, lockout (5/email+IP/15 min), token fingerprint changes on password change. Stateless 7-day HS256 JWT; no sessions table, no per-device revocation, no refresh rotation. | Keep ideas; replace token/session model. |
| Android | `minSdk 33`; cleartext HTTP permitted globally; user-editable server address; exported share/process-text intents; Keystore-sealed token; `allowBackup=false`. | minSdk 26, TLS only, fixed server, drop intents; keep Keystore sealing. |
| Quiz scoring | Keys withheld from takers; server-side scoring; idempotent `request_id`; per-user row lock; question snapshots on attempts. Two parallel systems (quizzes, MCQ bank). | Reuse the patterns, not the code; one assessment system. |
| Content | Topics authored in DB via admin UI/CSV. | Opposite of Git → validated build → delivery. Rebuild. |
| Structure | Single-file backend and frontend. | New backend as modules. |

## Reuse map
- **Reuse (patterns / small code):** Keystore token sealing, password-hash upgrade path, idempotent submissions with per-user locking, attempt snapshots, Compose theme scaffolding, CI skeleton, backup script idea.
- **Modify:** lockout → proper rate limiter; email OTP flow; Android build/signing workflow.
- **Rebuild:** backend modules, content pipeline, activation/entitlements, sessions/devices, progress model, editor + execution, lesson reader.
- **Discard:** teacher/parent/guardian/class/calendar/offered-course features, AI explanations/diagrams/OCR, Razorpay, Google login, highlights, ask-the-teacher, multi-language, admin analytics, the PWA.
