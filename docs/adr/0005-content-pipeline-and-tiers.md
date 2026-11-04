# 0005 Content pipeline and protection tiers
**Decision.** Textbook repo (canonical) → CI **validation build** → two artefacts:
- **Public package** (signed, versioned, per edition): lesson text, examples, expected output, diagrams, MCQ stems/options (shuffle-safe IDs, **no keys**), tracing programs, exercise statements/starter code/public tests.
- **Protected set** (loaded into the server database only): MCQ keys and explanations, tracing answers, hidden tests, reference solutions, hints/solutions per policy.
Delivery: manifest + per-file SHA-256, Ed25519 signature checked by the app; delta updates by content hash; bundled with the app only for the first lesson (fast start), the rest downloaded on demand. Edition/version compatibility declared in the manifest (`min_app_version`).
**Tiers.** (1) Server-only: keys, explanations (served per answer), hidden tests, solutions. (2) Downloadable offline: lessons/examples/diagrams, stored encrypted at rest with a per-account key released after login and entitlement check — deters casual copying and cache reuse after logout; a rooted device can still extract it. (3) Not preventable once displayed: screenshots, photos. We detect mass scraping (rate/volume), not individual copying.
**Consequences.** Offline practice MCQ cannot show answers without a key on the device; see ADR 0009 for the policy.

## Amendment (2026-11-04)
The textbook repo's approved architecture (permanent type-prefixed IDs; central ITM/EXR/ASM bank; LSN lessons in an app manifest;
Draft/Release validation) supersedes the directory layout originally sketched in `content-contract.md`. The two-output idea (public package +
protected set) and the protection tiers are unchanged. See `docs/content-interface.md`.
