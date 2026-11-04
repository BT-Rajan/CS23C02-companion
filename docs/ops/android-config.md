# Android build-time configuration
| Name | Meaning | Required |
|---|---|---|
| `API_BASE_URL` | `https://host[:port]`, no path, no trailing slash. HTTP is rejected at build time | Release: yes. Debug: falls back to `https://api.invalid` |
| `API_CERT_PINS` | Comma-separated `sha256/<base64>` SPKI pins (primary + backup), used by the API client | Required before release (enforced when pinning lands) |

**Local:** copy `android/.env.example` to `android/.env` and edit. **GitHub builds:** repository **Settings → Secrets and variables → Actions → Variables** → add `API_BASE_URL` (and `API_CERT_PINS`). A variable (not a secret) is right: the address is public the moment the app ships.
**Rules:** the value is baked into the APK at build time; students cannot change it. Never put secrets (passwords, API keys, signing keys) here: anything in an APK can be extracted. The backend's own secrets live in `backend/.env` and never reach the app.
**Emulator/local dev:** cleartext is disabled, so `http://10.0.2.2:8000` will not work. Use an HTTPS tunnel or a dev certificate; this is deliberate.
**Checks:** the build fails on a malformed address or a release build with the placeholder; a unit test asserts the baked address is HTTPS.
