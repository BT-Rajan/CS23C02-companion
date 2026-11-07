package app.cs23c02.companion.core

/**
 * Mirror of backend/app/activation/codes.py so the student sees a typo instantly, before any network call.
 * The server stays the only authority: a code that passes here can still be refused.
 * Format: 16 Crockford-base32 characters (15 random + 1 Luhn-mod-32 check), printed XXXX-XXXX-XXXX-XXXX.
 */
object ActivationCode {
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // no I, L, O, U
    const val LENGTH = 16
    private val N = ALPHABET.length

    /** Keeps only characters that can be part of a code, upper-cased, with Crockford fixes (O→0, I/L→1). Max 16. */
    fun clean(raw: String): String = raw.uppercase()
        .map { when (it) { 'O' -> '0'; 'I', 'L' -> '1'; else -> it } }
        .filter { it in ALPHABET }
        .joinToString("")
        .take(LENGTH)

    /** XXXX-XXXX-XXXX-XXXX for display (partial input is grouped as typed). */
    fun pretty(cleaned: String): String = cleaned.chunked(4).joinToString("-")

    fun checksumOk(code: String): Boolean =
        code.length == LENGTH && code.all { it in ALPHABET } && luhnSum(code, 1) % N == 0

    fun checkChar(body: String): Char = ALPHABET[(N - luhnSum(body, 2) % N) % N]

    private fun luhnSum(chars: String, startFactor: Int): Int {
        var factor = startFactor
        var total = 0
        for (ch in chars.reversed()) {
            val add = factor * ALPHABET.indexOf(ch)
            factor = if (factor == 2) 1 else 2
            total += add / N + add % N
        }
        return total
    }
}
