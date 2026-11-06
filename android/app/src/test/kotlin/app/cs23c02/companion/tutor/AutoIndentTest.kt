package app.cs23c02.companion.tutor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import app.cs23c02.companion.ui.screens.autoIndent
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoIndentTest {
    private fun enter(before: String): TextFieldValue {
        val old = TextFieldValue(before, TextRange(before.length))
        return autoIndent(old, TextFieldValue(before + "\n", TextRange(before.length + 1)))
    }

    @Test fun indentsAfterColon() = assertEquals("for m in marks:\n    ", enter("for m in marks:").text)
    @Test fun keepsIndentInsideBlock() = assertEquals("if x:\n    a = 1\n    ", enter("if x:\n    a = 1").text)
    @Test fun nestedBlock() = assertEquals("    if x:\n        ", enter("    if x:").text)
    @Test fun noIndentAtTopLevel() = assertEquals("x = 1\n", enter("x = 1").text)
    @Test fun cursorEndsAfterIndent() = assertEquals(TextRange(20), enter("for m in marks:").selection)

    @Test fun pasteIsLeftAlone() {
        val old = TextFieldValue("", TextRange(0))
        val pasted = TextFieldValue("for a in b:\n", TextRange(12))
        assertEquals(pasted, autoIndent(old, pasted))
    }
}
