# Gmail SMTP setup
1. Use a dedicated Google account for the app (not a personal one).
2. Enable 2-Step Verification, then create an **App Password** (Google Account → Security → App passwords).
3. Put these in `backend/.env` (never commit it):
```
EMAIL_BACKEND=smtp
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USER=your.address@gmail.com
SMTP_PASSWORD=<16-character app password>
SMTP_FROM=CS23C02 Companion <your.address@gmail.com>
```
4. Test: `python -m app.cli send-test-email you@example.com`.
Limits and caveats: see ADR 0011.
