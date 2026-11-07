# 0004 Accounts, sessions, recovery
**Decision.**
- Registration: email + password. Email verified by a 6-digit code (hashed, 10-minute expiry, 5 attempts, single use). Nothing else is collected (no name, phone, age).
- Passwords: Argon2id (m=64 MiB, t=3, p=1 initial; tuned on target hardware), minimum 10 characters, no composition rules, max 128, block a small common-password list. Hashes upgraded on login when parameters change.
- Recovery ("forgot password"): emailed 6-digit code, same properties as above; resend cooldown 60 s, 5 sends/day. Responses are identical whether or not the email exists (no enumeration). A successful reset revokes **all** sessions.
- Sessions: short-lived access token (15 min, signed) + opaque 256-bit refresh token stored hashed server-side with rotation and **reuse detection** (reuse revokes the token family). 90-day idle expiry. Refresh token sealed in Android Keystore.
- Devices: up to 3 active sessions; the oldest is revoked when a fourth signs in (the user sees "signed out because you signed in on another device"). Device replacement therefore never blocks access. Reinstall = sign in, no re-activation.
- Anomalies (many distinct sessions/IPs in a short window) trigger *email re-verification*, never an account lock. Repeated trigger raises a support flag.
- Login throttling: per account + per IP with exponential delay; failures do not reveal whether the email exists.
**Consequences.** Credential sharing is deterred and detectable, not prevented. Email access equals account access (standard); loss of email access is handled by support with proof of purchase.

**Amendment 2026-11-06 (built, K9).** Signals come from the sessions table only (no new tracking): new sign-ins in 24 h
(default 6), distinct sign-in networks in 24 h (keyed address hashes, default 4), device-cap evictions in 7 days (default 4);
all configurable (`ANOMALY_*`). Any one starts a 7-day window (`REVERIFY_WINDOW_DAYS`); every session that existed then or is
created during it gets `403 reverify_required` on everything except who-am-I, sign-out and re-verification until it enters a
6-digit code emailed to the **account's** address (same code rules as verification). Confirmation is **per session**, so the
owner confirms each of their devices once and a friend needs a freshly forwarded code. No re-trigger while a window is open.
Three triggers in 30 days set a support flag (`app.cli flagged-accounts`, `clear-flag --note`); people decide, nothing is locked.
Sign-in, sign-out and password reset always work.
