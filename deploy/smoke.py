#!/usr/bin/env python3
"""End-to-end smoke test against a running stack, over real HTTPS through Caddy (A8). Standard library only.

  python3 deploy/smoke.py --base https://staging.example.org --email you+smoke1@example.org --activation-code XXXX-... \
      [--cafile caddy-root.crt] [--otp-cmd "docker compose logs api"]

Without --otp-cmd it asks you for the code from the verification email (real Gmail delivery, K6). Uses the content fixture's
EXR-0001 and ASM-0002, so the edition the activation code belongs to must have the fixture (or a package with those IDs).
Each step prints PASS or stops with the reason. It creates one account; delete it afterwards on a production database.
"""
import argparse
import json
import re
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

PW = "smoke test passphrase 1"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    ap.add_argument("--email", required=True)
    ap.add_argument("--activation-code", required=True)
    ap.add_argument("--cafile")
    ap.add_argument("--otp-cmd", help="shell command whose output contains the verification email (console mail backend)")
    a = ap.parse_args()
    ctx = ssl.create_default_context(cafile=a.cafile) if a.cafile else ssl.create_default_context()
    token = {}

    def call(method, path, body=None, expect=200, raw=False):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(a.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if "access" in token:
            req.add_header("Authorization", "Bearer " + token["access"])
        try:
            with urllib.request.urlopen(req, context=ctx, timeout=30) as r:
                status, headers, payload = r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            status, headers, payload = e.code, e.headers, e.read()
        if status != expect:
            sys.exit(f"FAIL {method} {path}: HTTP {status} (expected {expect}): {payload[:300]!r}")
        if raw:
            return headers, payload
        return json.loads(payload) if payload else None

    def ok(msg):
        print("PASS", msg)

    if not a.base.startswith("https://"):
        sys.exit("FAIL --base must be https://")
    assert call("GET", "/healthz")["ok"] is True
    ok("API reachable over HTTPS")
    headers, page = call("GET", "/", raw=True)
    assert b"CS23C02 Companion" in page and b"{{" not in page, "landing page not rendered"
    assert "default-src 'none'" in headers.get("Content-Security-Policy", "") and "max-age" in headers.get("Strict-Transport-Security", "")
    ok("landing page served with CSP and HSTS")
    call("GET", "/docs", expect=404)
    ok("API docs are not exposed")

    call("POST", "/v1/auth/register", {"email": a.email, "password": PW}, expect=202)
    code = None
    if a.otp_cmd:
        for _ in range(20):
            out = subprocess.run(a.otp_cmd, shell=True, capture_output=True, text=True).stdout
            found = re.findall(r"to=" + re.escape(a.email) + r".*?code is (\d{6})", out, re.S)
            if found:
                code = found[-1]
                break
            time.sleep(1)
    else:
        code = input(f"Code emailed to {a.email}: ").strip()
    if not code:
        sys.exit("FAIL no verification code")
    call("POST", "/v1/auth/verify-email", {"email": a.email, "code": code})
    t = call("POST", "/v1/auth/login", {"email": a.email, "password": PW, "device_label": "smoke"})
    token["access"] = t["access_token"]
    ok("register, verify email, sign in")

    me = call("POST", "/v1/activation", {"code": a.activation_code})
    ok(f"activation ({json.dumps(me)[:80]})")
    m = call("GET", "/v1/content/manifest")
    assert m["files"], "manifest lists no files"
    ok(f"signed content manifest delivered ({len(m['files'])} files)")
    call("GET", f"/v1/content/files/{m['files'][0]['sha256']}", raw=True)
    ok("content file delivered")

    r = call("POST", "/v1/exercises/EXR-0001/submissions", {"code": "n = int(input())\nprint(n * 2)\n"})
    assert r["passed"] is True and r["hidden"]["passed"] == r["hidden"]["total"], r
    ok("exercise graded by the sandbox: right answer passes hidden tests")
    probe = ("import socket\ns = socket.socket()\ns.settimeout(2)\n"
             "try:\n    s.connect(('1.1.1.1', 80))\n    print('NET')\nexcept OSError:\n    print('no network')\n")
    r = call("POST", "/v1/exercises/EXR-0001/submissions", {"code": probe})
    assert r["passed"] is False and "NET" not in json.dumps(r["public"]), r
    ok("student code has no network")

    att = call("POST", "/v1/assessments/ASM-0002/attempts", {})
    ans = {q["item_id"]: q["options"][0]["id"] for q in att["questions"]}
    call("PUT", f"/v1/assessments/attempts/{att['attempt_id']}/answers", {"seq": 1, "answers": ans})
    res = call("POST", f"/v1/assessments/attempts/{att['attempt_id']}/submit", {"request_id": str(uuid.uuid4())})
    assert res["status"] == "submitted" and res["max_score"] == len(ans), res
    ok(f"assessment scored on the server ({res['score']}/{res['max_score']})")
    p = call("GET", "/v1/progress")
    ok(f"progress ({', '.join(sorted(p))})")
    print("SMOKE TEST PASSED")


if __name__ == "__main__":
    main()
