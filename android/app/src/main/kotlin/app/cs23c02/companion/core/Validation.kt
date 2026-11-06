package app.cs23c02.companion.core

/** Client-side checks only save a round trip; the server repeats every check (backend/app/security.py). */
object Validation {
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val SIX_DIGITS = Regex("^\\d{6}$")

    fun emailError(email: String): String? = when {
        email.isBlank() -> "Enter your email address."
        !EMAIL.matches(email.trim()) -> "That doesn't look like an email address."
        else -> null
    }

    fun passwordError(password: String, email: String): String? {
        val pw = password.lowercase()
        val e = email.trim().lowercase()
        return when {
            password.length < 10 || password.length > 128 -> "Use a password of 10 to 128 characters."
            pw == e || (e.contains('@') && pw == e.substringBefore('@')) -> "That password is too easy to guess. Please choose another."
            else -> null
        }
    }

    fun codeError(code: String): String? = if (SIX_DIGITS.matches(code)) null else "Enter the 6-digit code from the email."

    /** Digits only, at most 6: lets the student paste "123 456" from an email. */
    fun cleanSixDigits(raw: String): String = raw.filter { it.isDigit() }.take(6)
}
