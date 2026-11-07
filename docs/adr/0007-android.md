# 0007 Android: Kotlin + Jetpack Compose, minSdk 26
**Decision.** Kotlin, Compose, Navigation, Room (progress/event queue), WorkManager (sync), OkHttp + kotlinx.serialization. `minSdk 26`, current `targetSdk`. TLS only (`cleartextTrafficPermitted=false`), fixed server host, certificate pinning to our intermediate with a backup pin, `allowBackup=false`, R8 shrinking, no exported components except the launcher activity (and the QR https App Link).
**Consequences.** Covers far more older phones than Zigora's minSdk 33. Performance budget tracked from the first screen (startup, memory). No share/process-text intents.

## Amendment (2026-11-04): server address
The server address is a **build-time** setting (`API_BASE_URL`, read from `android/.env` locally or a GitHub repository variable in CI), baked into `BuildConfig` and never editable by the student. The build rejects anything but `https://host[:port]`. See `docs/ops/android-config.md`.

## Amendment (2026-11-06): A2 storage and sync
Lessons and progress are stored as small files in `noBackupFilesDir` (content-addressed package files + one atomically replaced
`current` file; one progress JSON) instead of Room, and progress syncs when Home opens and after each event instead of WorkManager.
The data is small and append-only, so this is simpler to verify; Room/WorkManager can replace it behind `ReaderRepository` if needed.
Release builds also require `CONTENT_SIGNING_PUBKEY` (the Ed25519 public key that verifies packages, via Tink).
