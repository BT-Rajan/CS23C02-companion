import hashlib
import hmac
import secrets

import jwt
from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError

from .config import get_settings

_s = get_settings()
_ph = PasswordHasher(time_cost=_s.argon_time_cost, memory_cost=_s.argon_memory_kb, parallelism=1)
_DUMMY_HASH = _ph.hash("timing-equaliser-not-a-real-password")

_COMMON = {"password", "password1", "password12", "password123", "1234567890", "12345678910", "qwertyuiop", "qwerty12345",
           "iloveyou12", "letmein1234", "welcome1234", "abcdefghij", "0123456789", "python12345", "pythonpython", "1q2w3e4r5t"}


def hash_password(pw: str) -> str:
    return _ph.hash(pw)


def verify_password(stored: str, pw: str) -> bool:
    try:
        return _ph.verify(stored, pw)
    except (VerificationError, InvalidHashError):
        return False


def spend_password_time(pw: str) -> None:
    """Burn the same CPU as a real check so unknown emails cannot be told apart by timing."""
    verify_password(_DUMMY_HASH, pw)


def needs_rehash(stored: str) -> bool:
    return _ph.check_needs_rehash(stored)


def validate_password(pw: str, email: str) -> None:
    if not 10 <= len(pw) <= 128:
        raise ValueError("Use a password of 10 to 128 characters.")
    if pw.lower() in _COMMON or pw.lower() == email.lower() or pw.lower() == email.split("@")[0].lower():
        raise ValueError("That password is too easy to guess. Please choose another.")


def sha256_hex(v: str) -> str:
    return hashlib.sha256(v.encode()).hexdigest()


def hmac_hex(pepper: str, msg: str) -> str:
    return hmac.new(pepper.encode(), msg.encode(), hashlib.sha256).hexdigest()


def ip_hash(ip: str) -> str:
    return hmac_hex(_s.aux_pepper, "ip|" + ip)[:16]


def new_refresh_token() -> str:
    return secrets.token_urlsafe(32)


def make_access_token(user_id: str, session_id: str, now_ts: int) -> str:
    return jwt.encode({"sub": user_id, "sid": session_id, "typ": "access", "iat": now_ts,
                       "exp": now_ts + _s.access_ttl_seconds}, _s.jwt_secret, algorithm="HS256")


def decode_access_token(token: str) -> dict | None:
    try:
        c = jwt.decode(token, _s.jwt_secret, algorithms=["HS256"], options={"require": ["exp", "iat", "sub", "sid"]})
    except jwt.PyJWTError:
        return None
    return c if c.get("typ") == "access" else None
