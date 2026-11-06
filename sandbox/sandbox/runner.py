"""One sandboxed run of a student's program (ADR 0006 §4).

Layers, outermost first:
1. A fresh bubblewrap container per run: new user, PID, network, IPC, UTS and mount namespaces; the filesystem is only /usr
   (read-only), a small empty /tmp, /proc and /dev; no network at all; the environment is cleared; it dies with its parent.
2. Each concurrent job gets its own unprivileged host UID from a slot pool (when the service starts as root, as it must in
   production), so per-user limits such as the process count belong to that one job and a fork bomb cannot starve others.
3. Resource limits: CPU seconds, address space, file size (also caps stdout, which goes to a file), open files, processes.
4. A wall-clock watchdog kills the whole process group.
5. Inside, the ADR 0006 import allow-list (defence in depth only).
"""
from __future__ import annotations

import dataclasses
import os
import pathlib
import resource
import queue
import shutil
import signal
import subprocess
import tempfile
import time

PYTHON = os.environ.get("SANDBOX_PYTHON", "/usr/bin/python3.12")  # the book's pinned Python (A5)
HARNESS = pathlib.Path(__file__).with_name("harness.py")
NOBODY = 65534
SLOT_BASE_UID = int(os.environ.get("SANDBOX_SLOT_BASE_UID", "61000"))
SLOTS = int(os.environ.get("SANDBOX_SLOTS", "4"))

# One host UID per concurrent job. Started without root (developer machines), there is a single shared slot instead:
# runs are serialised and the process limit is shared with the service, which is not acceptable in production.
_slots: "queue.Queue[int | None]" = queue.Queue()
if os.geteuid() == 0:
    for i in range(SLOTS):
        _slots.put(SLOT_BASE_UID + i)
else:
    _slots.put(None)


def isolation() -> str:
    return "per-job uid" if os.geteuid() == 0 else "shared uid (development only)"


@dataclasses.dataclass(frozen=True)
class Limits:
    wall_s: float = 5.0
    cpu_s: int = 2
    memory_mb: int = 256
    file_bytes: int = 1 << 20       # any single file, including stdout
    output_bytes: int = 64 << 10    # what is reported back
    nofile: int = 64
    nproc: int = 32
    tmp_bytes: int = 4 << 20


@dataclasses.dataclass
class RunResult:
    status: str          # ok | error | timeout | memory | output_limit | sandbox_error
    exit_code: int | None
    stdout: str
    stderr: str
    duration_ms: int
    error: str | None = None   # last line of the traceback, e.g. "ZeroDivisionError: division by zero"


class SandboxUnavailable(Exception):
    pass


def available() -> bool:
    return shutil.which("bwrap") is not None and os.path.exists(PYTHON)


def _bwrap_args(code_dir: str, limits: Limits) -> list[str]:
    # "/tmp" and "/code" below are paths INSIDE the container: a fresh private tmpfs and a read-only bind.
    return ["bwrap", "--unshare-all", "--unshare-user", "--disable-userns", "--die-with-parent", "--new-session",
            "--uid", str(NOBODY), "--gid", str(NOBODY),
            "--ro-bind", "/usr", "/usr", "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
            "--symlink", "usr/bin", "/bin",
            "--size", str(limits.tmp_bytes), "--tmpfs", "/tmp", "--proc", "/proc", "--dev", "/dev",  # nosec B108
            "--ro-bind", code_dir, "/code", "--chdir", "/tmp", "--clearenv", "--setenv", "PYTHONIOENCODING", "utf-8",  # nosec B108
            PYTHON, "-I", "-S", "-B", "/code/harness.py"]


def _wrapper(uid: int | None, limits: Limits) -> list[str]:
    """setpriv drops to the slot's UID with no groups and no capabilities; prlimit sets the resource limits. Both exec, so
    nothing runs in-process (no preexec_fn, which is unsafe in a threaded server)."""
    mem = limits.memory_mb << 20
    w = []
    if uid is not None:
        w += ["setpriv", f"--reuid={uid}", f"--regid={uid}", "--clear-groups", "--inh-caps=-all", "--bounding-set=-all",
              "--no-new-privs"]
    w += ["prlimit", f"--cpu={limits.cpu_s}:{limits.cpu_s + 1}", f"--as={mem}:{mem}",
          f"--fsize={limits.file_bytes}:{limits.file_bytes}", f"--nofile={limits.nofile}:{limits.nofile}",
          f"--nproc={limits.nproc}:{limits.nproc}", "--core=0:0", "--"]
    return w


def _last_error(stderr: str) -> str | None:
    lines = [l for l in stderr.strip().splitlines() if l.strip()]
    return lines[-1][:300] if lines else None


def run(code: str, stdin: str = "", limits: Limits = Limits()) -> RunResult:
    if not available():
        raise SandboxUnavailable("bubblewrap or the pinned Python is missing")
    uid = _slots.get()  # blocks while every slot is busy: concurrency is bounded by the pool
    try:
        return _run(code, stdin, limits, uid)
    finally:
        _slots.put(uid)


def _run(code: str, stdin: str, limits: Limits, uid: int | None) -> RunResult:
    work = tempfile.mkdtemp(prefix="sbx-")  # 0700, random name
    try:
        code_dir = os.path.join(work, "code")
        os.mkdir(code_dir, 0o700)
        pathlib.Path(code_dir, "main.py").write_text(code, encoding="utf-8")
        shutil.copy(HARNESS, os.path.join(code_dir, "harness.py"))
        # Only this job's UID can read its code: the directory and files belong to it, with no access for anyone else.
        owner = uid if uid is not None else os.getuid()
        for f in os.listdir(code_dir):
            os.chmod(os.path.join(code_dir, f), 0o400)
            os.chown(os.path.join(code_dir, f), owner, owner)
        os.chmod(code_dir, 0o500)
        os.chown(code_dir, owner, owner)
        os.chown(work, owner, owner)
        out_path, err_path = os.path.join(work, "out"), os.path.join(work, "err")
        t0 = time.monotonic()
        with open(out_path, "wb") as out, open(err_path, "wb") as err:
            p = subprocess.Popen(_wrapper(uid, limits) + _bwrap_args(code_dir, limits), stdin=subprocess.PIPE, stdout=out,
                                 stderr=err, start_new_session=True, close_fds=True, env={})  # noqa: S603 - fixed argv
            timed_out = False
            try:
                p.communicate(stdin.encode("utf-8"), timeout=limits.wall_s)
            except subprocess.TimeoutExpired:
                timed_out = True
                try:
                    os.killpg(p.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                p.wait()
            except BrokenPipeError:
                p.wait()
        ms = int((time.monotonic() - t0) * 1000)
        raw_out = pathlib.Path(out_path).read_bytes()
        raw_err = pathlib.Path(err_path).read_bytes()
        stdout = raw_out[: limits.output_bytes].decode("utf-8", "replace")
        stderr = raw_err[: limits.output_bytes].decode("utf-8", "replace")
        rc = p.returncode
        if raw_err.startswith((b"bwrap:", b"setpriv:", b"prlimit:")):
            return RunResult("sandbox_error", rc, "", "", ms, stderr.strip()[:300])
        if timed_out or rc in (-signal.SIGXCPU, -signal.SIGKILL, 128 + signal.SIGXCPU, 128 + signal.SIGKILL):
            return RunResult("timeout", rc, stdout, stderr, ms, "The program ran too long and was stopped.")
        if rc in (-signal.SIGXFSZ, 128 + signal.SIGXFSZ) or len(raw_out) > limits.output_bytes:
            return RunResult("output_limit", rc, stdout, stderr, ms, "The program printed too much and was stopped.")
        err_line = _last_error(stderr)
        if rc != 0 and err_line and err_line.startswith("MemoryError"):
            return RunResult("memory", rc, stdout, stderr, ms, "The program used too much memory.")
        if rc != 0:
            return RunResult("error", rc, stdout, stderr, ms, err_line)
        return RunResult("ok", 0, stdout, stderr, ms)
    finally:
        shutil.rmtree(work, ignore_errors=True)
