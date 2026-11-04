# Android CI: how to get and test a build
## Getting the APK
1. GitHub → repository **CS23C02-companion** → **Actions** → the latest successful **android** run.
2. Scroll to **Artifacts**, download `app-debug-apk`, unzip it.
3. Move `app-debug.apk` to the phone and open it. Allow "install unknown apps" for the app you used to open it (a one-time Android prompt). Debug builds are for testing only and are not distributed.
## Reporting a problem
Send: what you tapped, what you expected, what happened, the phone model and Android version, and a screenshot. A one-line description is enough.
## What the assistant can and cannot verify
| Verified automatically in CI | Needs the owner's phone |
|---|---|
| It compiles; lint passes; unit tests pass; no known-vulnerable dependencies | Layout and readability; typing comfort in the editor; startup speed and smoothness on a low-end phone; real network behaviour |
## Reference device
Owner's phone: **Redmi A7, 3 GB RAM, Android 14** (model name to be confirmed; the Redmi 7A shipped with Android 9). Treat it as the low-end target: 3 GB RAM, API 34. Performance numbers measured elsewhere are labelled "emulator" or "unmeasured".

## Installing from a phone (first time)
GitHub's artifact download needs you to be signed in to GitHub, so the easiest route is: on a computer, open the run's page, download `app-debug-apk`, unzip, and send `app-debug.apk` to the phone (USB, Drive, email to yourself). The first debug build (run 41913367690) is a hello-world that shows "CS23C02 Companion / Build pipeline OK". Its only purpose is to confirm it installs and opens on the Redmi.

