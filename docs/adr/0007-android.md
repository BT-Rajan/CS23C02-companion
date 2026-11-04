# 0007 Android: Kotlin + Jetpack Compose, minSdk 26
**Decision.** Kotlin, Compose, Navigation, Room (progress/event queue), WorkManager (sync), OkHttp + kotlinx.serialization. `minSdk 26`, current `targetSdk`. TLS only (`cleartextTrafficPermitted=false`), fixed server host, certificate pinning to our intermediate with a backup pin, `allowBackup=false`, R8 shrinking, no exported components except the launcher activity (and the QR https App Link).
**Consequences.** Covers far more older phones than Zigora's minSdk 33. Performance budget tracked from the first screen (startup, memory). No share/process-text intents.

## Amendment (2026-11-04): server address
The server address is a **build-time** setting (`API_BASE_URL`, read from `android/.env` locally or a GitHub repository variable in CI), baked into `BuildConfig` and never editable by the student. The build rejects anything but `https://host[:port]`. See `docs/ops/android-config.md`.
