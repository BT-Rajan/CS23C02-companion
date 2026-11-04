"""Activation code format: 16 Crockford-base32 characters = 15 random (75 bits) + 1 Luhn-mod-32 check character.
Printed as XXXX-XXXX-XXXX-XXXX. Input is case-insensitive; spaces/hyphens ignored; O->0 and I/L->1 as in Crockford."""
import secrets

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"  # no I, L, O, U
N = len(ALPHABET)
_IDX = {c: i for i, c in enumerate(ALPHABET)}
_FIX = str.maketrans({"O": "0", "I": "1", "L": "1"})


def _luhn_sum(chars: str, factor: int) -> int:
    total = 0
    for ch in reversed(chars):
        add = factor * _IDX[ch]
        factor = 1 if factor == 2 else 2
        total += add // N + add % N
    return total


def check_char(body: str) -> str:
    return ALPHABET[(N - _luhn_sum(body, 2) % N) % N]


def generate() -> str:
    body = "".join(secrets.choice(ALPHABET) for _ in range(15))
    return body + check_char(body)


def normalise(raw: str) -> str | None:
    s = "".join(raw.split()).replace("-", "").upper().translate(_FIX)
    if len(s) != 16 or any(c not in _IDX for c in s):
        return None
    return s


def checksum_ok(code: str) -> bool:
    return _luhn_sum(code, 1) % N == 0


def pretty(code: str) -> str:
    return "-".join(code[i:i + 4] for i in range(0, 16, 4))
