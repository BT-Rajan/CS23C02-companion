# 0012 Android builds run on GitHub Actions
**Status:** Approved by owner (2026-11-04).
**Context.** The assistant's sandbox cannot reach the hosts Android builds download from (`dl.google.com`, `maven.google.com`, `services.gradle.org`, `repo.maven.apache.org`, `plugins.gradle.org`), and the owner could not change the network allowlist on their plan. Without a build, no Android code can be compiled or tested.
**Decision.** Compile, lint and unit-test the Android app on GitHub Actions (hosted runners have normal internet access). The assistant reads results through the GitHub API (`api.github.com`, reachable from the sandbox). If full logs cannot be fetched from the sandbox, the workflow publishes a short log tail to a `ci-logs` branch, which the assistant can read through `github.com`.
**Rules.**
1. First step is a minimal "hello world" app that proves the loop (push → build → read result → APK artifact) before any real feature depends on it.
2. No Android screen or feature is reported as done until the workflow shows it compiled, passed lint and passed its tests. Anything not covered by automated tests is reported as "built, not verified on a device".
3. Each run uploads a debug APK as an artifact (never a release/signing build). Release signing keys stay out of the repository and the CI logs; they are added later as GitHub encrypted secrets.
4. Pinned versions: Gradle wrapper, Android Gradle Plugin, Kotlin and Compose BOM versions are pinned and checked in; dependency updates are deliberate. `gradle/actions/wrapper-validation` verifies the wrapper jar.
5. Dependency audit and secret scanning also run in CI for the Android module.
**Consequences.** Each fix cycle takes minutes, not seconds, so changes are batched into small, testable slices. The assistant cannot observe the app running; look and feel, performance and usability are judged by the owner on a real phone (see `docs/ops/android-ci.md`). A reference low-end device is still needed for the performance targets.

## Amendment (2026-11-04): Gradle wrapper
The sandbox cannot download Gradle to generate a wrapper, so the first workflow pins Gradle with `gradle/actions/setup-gradle` (`gradle-version: 8.10.2`). Rule 4 (wrapper validation) applies once a wrapper is generated and committed; until then versions are pinned in the workflow and `android/build.gradle.kts`.

## Evidence (2026-11-04): the loop works
Hello-world app, run 41913367690: checkout, JDK 17, Gradle 8.10.2, `lintDebug` + `testDebugUnitTest` + `assembleDebug` all executed and passed (BUILD SUCCESSFUL in 2m 20s); the debug APK (8.3 MB) and reports uploaded as artifacts; the log tail was published to the `ci-logs` branch and read back from the sandbox. Run status and step results are read via `api.github.com`. Direct job-log download answers with a redirect (HTTP 302) to a storage host that was not tried; the `ci-logs` branch is the supported way to read logs.

