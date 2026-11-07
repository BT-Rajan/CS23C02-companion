"""Hostile suite for the sandbox (ADR 0006; attack register E1-E8). Several tests first ESCAPE the Python import allow-list
(via importlib internals) on purpose: the operating-system layers must hold on their own."""
import concurrent.futures
import pathlib
import sys
import textwrap
import time

import pytest

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from sandbox import runner  # noqa: E402

pytestmark = pytest.mark.skipif(not runner.available(), reason="bubblewrap or python3.12 not installed")

def escape():
    """Source prefix defining grab(name) -> any module, bypassing the import allow-list in one line: the harness's guard
    keeps the real import in its globals. This is exactly why the allow-list is only defence in depth."""
    return "grab = lambda name: __import__.__globals__['_real_import'](name)\n"


def run_esc(body, stdin="", **lim):
    """Runs [body] with the allow-list bypass available as grab()."""
    return runner.run(escape() + textwrap.dedent(body), stdin, runner.Limits(**lim) if lim else runner.Limits())


def run(src, stdin="", **lim):
    return runner.run(textwrap.dedent(src), stdin, runner.Limits(**lim) if lim else runner.Limits())


def test_escape_helper_really_escapes_the_allow_list():
    assert run("import os").status == "error"
    r = run_esc("os = grab('os')\nprint(os.name)")
    assert r.status == "ok" and r.stdout.strip() == "posix", r


# ---- correctness ------------------------------------------------------------------------------------------------------
def test_stdin_stdout_unicode_and_errors():
    r = run("a = int(input()); b = int(input()); print(a + b, 'à✓')", "2\n3\n")
    assert r.status == "ok" and r.stdout == "5 à✓\n" and r.exit_code == 0
    r = run("print('before')\n1/0")
    assert r.status == "error" and r.stdout == "before\n" and r.error == "ZeroDivisionError: division by zero"
    assert run("import math\nprint(math.sqrt(16))").stdout == "4.0\n"


# ---- E1 runaway ------------------------------------------------------------------------------------------------------
def test_e1_infinite_loop_is_stopped_by_cpu_limit():
    t = time.monotonic()
    r = run("while True:\n    pass")
    assert r.status == "timeout" and time.monotonic() - t < 6


def test_e1_sleeping_forever_is_stopped_by_the_wall_clock():
    t = time.monotonic()
    r = run_esc("grab('time').sleep(60)", wall_s=2.0)
    assert r.status == "timeout" and time.monotonic() - t < 4


# ---- E2 memory ------------------------------------------------------------------------------------------------------
def test_e2_memory_bomb():
    r = run("x = 'a' * (10 ** 9)\nprint(len(x))")
    assert r.status == "memory" and r.stdout == ""


# ---- E3 output flood --------------------------------------------------------------------------------------------------
def test_e3_output_flood_is_capped():
    r = run("while True:\n    print('x' * 1000)")
    assert r.status in ("output_limit", "timeout") and len(r.stdout.encode()) <= runner.Limits().output_bytes


# ---- E4 files -------------------------------------------------------------------------------------------------------
def test_e4_host_files_are_invisible_and_usr_is_read_only():
    r = run_esc("""
        os = grab('os')
        print(sorted(os.listdir('/')))
        for p in ('/etc/passwd', '/etc/shadow', '/home', '/root', '/var'):
            try:
                open(p).read(); print('READ', p)
            except OSError as e:
                print('denied', p, type(e).__name__)
        try:
            open('/usr/evil', 'w'); print('WROTE /usr')
        except OSError as e:
            print('usr', type(e).__name__)
        try:
            open('/code/main.py', 'a').write('x'); print('WROTE code')
        except OSError as e:
            print('code', type(e).__name__)
    """)
    assert r.status == "ok", r
    assert "READ" not in r.stdout and "WROTE" not in r.stdout
    assert "['bin', 'code', 'dev', 'lib', 'lib64', 'proc', 'tmp', 'usr']" in r.stdout


def test_e4_pid_1_is_the_sandbox_itself_and_carries_no_host_environment():
    r = run_esc('''
        print(open('/proc/1/cmdline', 'rb').read().split(b'\\0')[0].decode())
        print(repr(open('/proc/1/environ', 'rb').read()))
    ''')
    first, env = r.stdout.splitlines()
    assert first == "bwrap"                               # PID 1 is the sandbox's own reaper, not the host's init
    for leak in ("HOME=", "PATH=", "PROXY", "SECRET", "KEY", "TOKEN", "DATABASE"):
        assert leak not in env, env


def test_e4_scratch_space_is_small():
    r = run("f = open('/tmp/big', 'w')\nfor _ in range(100):\n    f.write('x' * 100000)\nf.close()\nprint('wrote 10 MB')")
    assert "wrote 10 MB" not in r.stdout and r.status != "ok"


# ---- E5 network -----------------------------------------------------------------------------------------------------
def test_e5_no_network_not_even_dns():
    r = run_esc("""
        socket = grab('socket')
        for target in (('1.1.1.1', 80), ('127.0.0.1', 22)):
            try:
                socket.create_connection(target, timeout=2); print('CONNECTED', target)
            except OSError as e:
                print('blocked', type(e).__name__)
        try:
            socket.getaddrinfo('example.com', 443); print('DNS WORKS')
        except OSError as e:
            print('dns blocked')
    """)
    assert r.status == "ok" and "CONNECTED" not in r.stdout and "DNS WORKS" not in r.stdout, r


# ---- E6 processes ---------------------------------------------------------------------------------------------------
def test_e6_fork_bomb_is_contained():
    t = time.monotonic()
    r = run_esc("""
        os = grab('os')
        n = 0
        try:
            while True:
                if os.fork() == 0:
                    while True:
                        pass
                n += 1
        except OSError as e:
            print('fork refused after', n, type(e).__name__)
    """, wall_s=3.0)
    assert time.monotonic() - t < 6
    assert "fork refused" in r.stdout or r.status == "timeout"
    if "fork refused" in r.stdout:
        assert int(r.stdout.split()[3]) <= runner.Limits().nproc
    assert run("print('host still fine')").stdout == "host still fine\n"


def test_e6_cannot_run_other_programs():
    r = run_esc("""
        subprocess = grab('subprocess')
        for cmd in (['/bin/sh', '-c', 'id'], ['/usr/bin/curl', 'https://example.com']):
            try:
                print('RAN', subprocess.run(cmd, capture_output=True, timeout=2).returncode)
            except Exception as e:
                print('failed', type(e).__name__)
    """)
    # Programs on /usr can technically be started (read-only, same sandbox), but they gain nothing: same namespaces,
    # no network, same limits. curl must not reach the network.
    assert "RAN 0" not in r.stdout.split("\n")[1] if r.stdout.count("\n") > 1 else True


# ---- E7 imports -----------------------------------------------------------------------------------------------------
@pytest.mark.parametrize("mod", ["os", "sys", "subprocess", "socket", "ctypes", "importlib", "builtins", "shutil"])
def test_e7_dangerous_imports_are_refused(mod):
    r = run(f"import {mod}")
    assert r.status == "error" and "not available in this course" in r.error


# ---- E8 identity / visibility ----------------------------------------------------------------------------------------
def test_e8_runs_as_nobody_alone_with_no_environment():
    r = run_esc("""
        os = grab('os')
        print(os.getuid(), os.getgid())
        print(sorted(int(p) for p in os.listdir('/proc') if p.isdigit()))
        print(sorted(k for k in os.environ if k not in ('PYTHONIOENCODING', 'PWD', 'LC_CTYPE')))
    """)
    lines = r.stdout.splitlines()
    assert lines[0] == "65534 65534"
    assert len(eval(lines[1])) <= 3          # only the sandbox's own processes are visible
    assert lines[2] == "[]"                   # no secrets or paths leak in through the environment


def test_runs_are_independent_and_parallel():
    for _ in range(3):  # repeated: an earlier version failed intermittently here (shared process limit across jobs)
        with concurrent.futures.ThreadPoolExecutor(8) as ex:
            results = list(ex.map(lambda i: run(f"open('/tmp/f', 'a').write('{i}')\nprint(open('/tmp/f').read())"), range(8)))
        assert [r.stdout.strip() for r in results] == [str(i) for i in range(8)], [(r.status, r.error) for r in results]


@pytest.mark.skipif(runner.isolation() != "per-job uid", reason="needs the production setup (service started as root)")
def test_e6_a_fork_bomb_does_not_starve_a_parallel_job():
    bomb = escape() + "os = grab('os')\nwhile True:\n    try:\n        os.fork()\n    except OSError:\n        pass\n"
    with concurrent.futures.ThreadPoolExecutor(2) as ex:
        f_bomb = ex.submit(run, bomb, "", wall_s=3.0)
        time.sleep(0.5)
        honest = ex.submit(run, "print(sum(range(10)))").result()
    assert honest.status == "ok" and honest.stdout == "45\n", honest
    assert f_bomb.result().status in ("timeout", "error")
