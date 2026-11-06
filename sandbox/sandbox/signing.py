"""HMAC-SHA256 over timestamp + body for requests (API -> sandbox) and responses (sandbox -> API). ADR 0006 §4 asks for the
API and sandbox to authenticate each other and for results to be signed; mutual TLS on the private network is added at
deployment, this signature stays end-to-end."""
import hashlib
import hmac
import time

SKEW_S = 60


def sign(key: bytes, ts: str, body: bytes) -> str:
    return hmac.new(key, ts.encode() + b"." + body, hashlib.sha256).hexdigest()


def headers(key: bytes, body: bytes) -> dict:
    ts = str(int(time.time()))
    return {"X-Sandbox-Timestamp": ts, "X-Sandbox-Signature": sign(key, ts, body)}


def verify(key: bytes, ts: str | None, sig: str | None, body: bytes) -> bool:
    if not ts or not sig or not ts.isdigit() or abs(time.time() - int(ts)) > SKEW_S:
        return False
    return hmac.compare_digest(sign(key, ts, body), sig)
