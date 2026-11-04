# 0011 Email: Gmail SMTP via .env, behind a provider interface
**Context.** The owner will supply a Gmail account configured in `.env`. Email carries verification and recovery codes, so it is a critical path for first-time setup.
**Decision.**
- `app/mail.py` exposes `send_email(to, subject, body)` with backends `smtp` (default in prod), `console` (dev), `memory` (tests). `console`/`memory` are refused when `ENV=prod`.
- SMTP settings come only from environment/.env: `SMTP_HOST=smtp.gmail.com`, `SMTP_PORT=587` (STARTTLS), `SMTP_USER`, `SMTP_PASSWORD` (a Google **App Password**, which requires 2-Step Verification), `SMTP_FROM`. Never committed; `.env.example` holds placeholders only.
- Sending is done in a background task so response timing is identical for existing and non-existing emails.
- One-time codes are never logged in prod.
**Consequences / risks (accepted for now).**
- Consumer Gmail has daily sending limits (roughly 500 recipients/day; Workspace is higher) — fine for early release, a bottleneck for a large launch week. The interface lets us swap in a transactional provider later without touching callers.
- From-address is fixed to the authenticated Gmail account; no custom-domain SPF/DKIM alignment. Messages may land in spam for some providers; the app's email-code screen offers "resend" and tells the student to check spam.
- If Google disables the app password or flags the account, sign-up and recovery stop. Monitoring and a documented fallback (support-issued reset) are required before release.
