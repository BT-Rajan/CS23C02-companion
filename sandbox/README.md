# sandbox — graded runs of students' code (ADR 0006)
A separate deployable with **no database and no user data**. The API sends it code and tests over a private network; it runs
each test in a fresh container and returns a signed result.

```
SANDBOX_KEY=$(openssl rand -hex 32) uvicorn sandbox.service:app --host 10.0.0.5 --port 8090   # started as root, see below
python -m pytest sandbox/tests                                                                # hostile suite (needs bubblewrap)
```

## Layers (outermost first)
1. **bubblewrap container per run**: new user, PID, network, IPC, UTS and mount namespaces; filesystem = `/usr` read-only, a
   4 MB private `/tmp`, `/proc`, `/dev`, and the code read-only at `/code`; no network at all (not even DNS); empty environment;
   no nested user namespaces; dies with its parent.
2. **A UID per job** from a slot pool (`SANDBOX_SLOTS`, default 4, from `SANDBOX_SLOT_BASE_UID`), applied with `setpriv`
   (no groups, no capabilities, no-new-privs). Per-user limits therefore belong to one job: a fork bomb cannot starve another
   student's run. This needs the service to **start as root on its own VM/container**; without root it falls back to one shared
   slot (development only, `/healthz` reports the isolation level).
3. **Resource limits** via `prlimit`: CPU 2 s, address space 256 MB, file size 1 MB (stdout goes to a file, so this also caps
   output), 64 open files, 32 processes, no core dumps; plus a 5 s wall-clock watchdog that kills the process group.
4. **Import allow-list** (ADR 0006) inside — defence in depth only; the tests deliberately bypass it.

## Talking to the API
`POST /v1/grade` with `{"job_id", "code", "tests": [{"stdin", "expect"}]}`. Requests must carry
`X-Sandbox-Timestamp` + `X-Sandbox-Signature` = HMAC-SHA256(SANDBOX_KEY, timestamp + "." + body), be at most 60 s old and use a new
job id (replays are refused). Answers are signed the same way; the API rejects unsigned, forged or swapped answers. Put mutual TLS
on the private network as well; the signature stays end-to-end.

## Evidence
`tests/test_runner.py` (E1–E8, run as root in CI so per-job UIDs are exercised) and `tests/test_service.py`. Measured here: ≈ 35 ms per
run. **Not done:** seccomp filter (a further layer), gVisor/nsjail comparison, load test of the pool, third-party review (E8).
