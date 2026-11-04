import itertools

from app.activation import codes
from app.activation.service import code_hmac


def test_generated_codes_are_well_formed():
    seen = set()
    for _ in range(2000):
        c = codes.generate()
        assert len(c) == 16 and all(ch in codes.ALPHABET for ch in c) and codes.checksum_ok(c)
        seen.add(c)
    assert len(seen) == 2000  # no collisions in a small sample (75 random bits each)


def test_every_single_character_substitution_is_detected():
    for _ in range(50):
        c = codes.generate()
        for i, ch in itertools.product(range(16), codes.ALPHABET):
            if ch != c[i]:
                assert not codes.checksum_ok(c[:i] + ch + c[i + 1:])


def test_normalise_is_tolerant_of_how_students_type():
    c = codes.generate()
    p = codes.pretty(c)
    assert codes.normalise(p.lower()) == c
    assert codes.normalise(" " + p.replace("-", " ") + " ") == c
    assert codes.normalise("0OIL" + c[4:]) == "001" + "1" + c[4:]  # Crockford: O->0, I/L->1
    assert codes.normalise("short") is None and codes.normalise(c + "A") is None and codes.normalise("U" * 16) is None


def test_code_storage_is_a_keyed_hash_never_the_code():
    c = codes.generate()
    h = code_hmac(c)
    assert len(h) == 64 and c not in h and h != code_hmac(codes.generate())
