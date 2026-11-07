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


## What to check in the A1 build (sign-in and activation)
The A1 build talks to the server in `API_BASE_URL`. Until a staging server exists (roadmap A8) a phone build cannot sign in;
use the **screens** artifact to review layout, and check on the phone: it installs, the icon shows, Welcome opens, text fields
and the keyboard behave, and back works. Running the full flow on a phone needs a reachable HTTPS backend.
## Local developer checks
- `android/scripts/contract-test.sh`: the app's API client against a real backend (needs MySQL and `backend/.venv`).
- `gradle :app:testDebugUnitTest -Pscreenshots --tests '*ScreenshotTest*'`: PNGs in `android/app/build/screens`
  (set `ROBOLECTRIC_REPO` to a Maven Central mirror if Central throttles downloads).

## Trying the tutor and measuring Python on the phone (debug build)
1. Install the latest `app-debug-apk`. On Welcome tap **Try the tutor (test build only)** (no account or server needed).
2. **Ask for a program** → pick one → **Show me** (step through) or **I'll type it** (the tutor checks each line).
3. **Write your own code**: the header first says "Getting Python ready…", then **"Python ready in X s (worker|page) · last run Y ms"**.
   Please send those numbers, whether it says *worker* or *page*, and how typing and stepping feel. They are the performance gate
   (ADR 0006: ready ≤ 3 s after warm-up; smooth on a 3 GB phone).
4. Try a program with `input()`: the stepper asks for the input and continues.

## Trying the reader (debug build)
On Welcome tap **Try the sample lessons (test build only)**: five short SAMPLE lessons (not book content) open with Continue,
units, lesson pages, **Step through** and **Try it yourself** on every code example, and **Done · next lesson**. Close and reopen
the app: Continue should bring you back to the same lesson and place. Sample progress stays on the phone.
