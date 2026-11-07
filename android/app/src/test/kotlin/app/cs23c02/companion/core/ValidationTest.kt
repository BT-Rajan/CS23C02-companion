package app.cs23c02.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ValidationTest {
    @Test fun email() {
        assertNull(Validation.emailError("a.student@example.com"))
        assertNull(Validation.emailError("  a@b.in "))
        assertNotNull(Validation.emailError(""))
        assertNotNull(Validation.emailError("student"))
        assertNotNull(Validation.emailError("a@b"))
    }

    @Test fun passwordMirrorsServerRules() {
        assertNull(Validation.passwordError("correct horse", "s@example.com"))
        assertNotNull(Validation.passwordError("short", "s@example.com"))
        assertNotNull(Validation.passwordError("x".repeat(129), "s@example.com"))
        assertNotNull(Validation.passwordError("student.one", "student.one@example.com"))
        assertNotNull(Validation.passwordError("Student.One@Example.com", "student.one@example.com"))
    }

    @Test fun sixDigitCode() {
        assertNull(Validation.codeError("012345"))
        assertNotNull(Validation.codeError("12345"))
        assertEquals("123456", Validation.cleanSixDigits("123 456 7"))
    }
}
