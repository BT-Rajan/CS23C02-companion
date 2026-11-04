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
