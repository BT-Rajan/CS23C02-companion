# Backend (Phase 2: commercial access foundation)
FastAPI + MySQL 8. Implemented: registration with emailed verification code, login, rotating refresh tokens with reuse
detection, device cap, password reset by emailed code, one-time activation codes with atomic redemption, entitlement
check dependency, audit log, DB-backed rate limits, admin CLI for code batches. Phase 3 slice 1 adds signed content packages
(import, entitlement-gated manifest and files) and progress events with resume. **Not yet built:** assessments, execution, Android app.

## Run locally
```
python3 -m venv .venv && . .venv/bin/activate && pip install -r requirements.txt
cp .env.example .env     # fill in secrets (openssl rand -hex 32) and DB URLs; never commit .env
alembic upgrade head     # as the migration user
python -m app.cli print-grants --schema cs23c02 --user "'cs23c02_app'@'%'" | mysql -u <admin> -p
uvicorn app.main:app --reload
```
Dev mode prints emails to the console. Production requires `EMAIL_BACKEND=smtp` (Gmail: `docs/ops/gmail-smtp.md`).

## Admin CLI
```
python -m app.cli create-edition --id cs23c02-2026 --title "CS23C02 First Edition"
python -m app.cli create-batch --edition cs23c02-2026 --count 500 --label "print run 1" --out codes.csv   # state: unissued
python -m app.cli release-batch --batch 1      # when the books ship
python -m app.cli revoke-batch --batch 1       # contain a leak (activated books are untouched)
python -m app.cli batch-report --batch 1
python -m app.cli import-content --edition cs23c02-2026 --public OUT/public --protected OUT/protected.json [--publish]
python -m app.cli publish-release --release 1
python -m app.cli purge-rate-events            # run daily from cron
```
`codes.csv` is the only place plaintext codes exist (mode 0600). Send it to the printer over an encrypted channel and delete it.

## Tests
`python -m pytest` runs against a **real MySQL** (schema built by the migrations; runtime user has production-style grants).
See `docs/security/test-evidence.md`.
