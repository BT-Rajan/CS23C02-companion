# 0006 Python execution: hybrid, local-first
**Context.** Student code is untrusted. Performance on mid-range/older phones matters. No execution exists in Zigora.
**Decision.**
1. **Run (practice) executes on-device** in a WebAssembly CPython (Pyodide) inside a dedicated WebView with a Web Worker; no network, no JS bridge exposing app data, a minimal origin, CSP `default-src 'none'`. Zero server cost or risk; works offline; instant feedback after warm-up. The runtime is bundled in the APK (no download, ~10 MB compressed) and warmed in the background when a lesson with code opens.
2. **Hard stop:** a watchdog terminates the worker after N seconds or on output cap, then recreates it. Output is capped and truncated.
3. **Check/Submit (graded) executes on the server sandbox**, with hidden tests. Local results never affect scores or progress.
4. **Server sandbox** (separate host/VPC, no route to the database): gVisor (`runsc`) or nsjail with seccomp; per-job fresh container; read-only rootfs + small tmpfs; no network namespace; non-root; limits ≈ wall 5 s, CPU 2 s, memory 128 MB, pids 32, output 64 KB, file size 1 MB; pre-warmed pool; queue with per-user concurrency 1 and rate limits; API↔sandbox over mTLS on a private network; the client never talks to the sandbox; results are signed by the sandbox and verified by the API.
5. **Performance gate (Phase 5):** benchmark Pyodide vs MicroPython-WASM on a reference low-end device. Budget: first run ≤ 3 s after warm-up trigger, ≤ 150 MB resident. If Pyodide misses budget, fall back to server-run for "Run" on those devices (feature-flagged) rather than shipping a slow editor. CPython fidelity (error messages, float formatting) is the reason Pyodide is the default.
6. Allowed imports are an allow-list taken from the content (initially `math`, `random`, `statistics`, `string`, `datetime`, `collections`, `itertools`, `functools`, `re`, `json`), enforced in both runtimes; `input()` is fed from a controlled buffer.
**Consequences.** Most executions never reach the server, so abuse of the server is bounded by Submit rate limits. Release of code execution is gated on the sandbox test suite (Phase 5). Local execution protects our servers but not the integrity of local results — hence graded runs are server-side.

## Amendment (2026-11-06): server sandbox as built (A6)
The server sandbox uses **bubblewrap** (unprivileged namespaces, as used by Flatpak) instead of gVisor or nsjail: available as a
distribution package, no daemon, and testable in CI. Each run gets a fresh container (no network, `/usr` read-only, private 4 MB
`/tmp`, empty environment), its own host UID from a slot pool (so per-user process limits isolate jobs), `prlimit` resource limits and
a wall-clock watchdog. API ↔ sandbox requests and results are HMAC-signed with a shared key, time-limited and replay-protected; mutual
TLS is added at deployment. Production refuses to run student code inside the API process (`SANDBOX_MODE=http` required).
Graded runs use CPython 3.12, the book's pinned version. A seccomp filter and a gVisor comparison remain open (K32).
Details and evidence: `sandbox/README.md`.
