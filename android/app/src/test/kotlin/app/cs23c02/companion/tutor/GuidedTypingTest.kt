package app.cs23c02.companion.tutor

import app.cs23c02.companion.stepper.Recordings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuidedTypingTest {
    private val programs = Recordings.parsePrograms(File("src/main/assets/stepper/demos.json").readText())
    private val total = programs.single { it.id == "running-total" }   // marks = [72, 85, 64] / total = 0 / for m in marks: ...
    private val g = GuidedTyping(total)

    @Test fun emptyEditorAsksForTheFirstLineWithItsExplanationAndBookRef() {
        val r = g.check("")
        val f = r.focus as Focus.Next
        assertEquals(1, f.targetLine)
        assertEquals("Creates the variable marks and stores [72, 85, 64] in it.", f.target!!.explain)
        assertEquals("_ = [_, _, _]", f.target!!.shape)
        assertTrue(total.refsFor(1).any { it.title == "Variables and assignment" && it.sample })
        assertEquals(0, r.linesDone); assertEquals(7, r.linesTotal)
    }

    @Test fun matchingPrefixIsTypingNotAnError() {
        val f = g.check("marks = [72, 8").focus
        assertTrue(f is Focus.Typing && f.targetLine == 1)
    }

    @Test fun correctLinesAdvanceTheTutor() {
        val r = g.check("marks = [72, 85, 64]\ntotal = 0\n")
        assertEquals(2, r.linesDone)
        assertEquals(3, (r.focus as Focus.Next).targetLine)
    }

    @Test fun spacingAndQuoteStyleAreAccepted() {
        val r = g.check("marks=[72,85,64]\ntotal = 0\nfor m in marks:\n    total = total+m\naverage = total / len(marks)\nprint('Total:', total)\n")
        assertTrue(r.checks.all { it.ok })
        assertEquals(Verdict.EQUIVALENT, r.checks[0].verdict)
        assertEquals(Verdict.EQUIVALENT, r.checks[5].verdict)
    }

    @Test fun missingIndentIsExplained() {
        val f = g.check("marks = [72, 85, 64]\ntotal = 0\nfor m in marks:\ntotal = total + m\n").focus as Focus.Fix
        assertEquals(Verdict.INDENT, f.check.verdict)
        assertEquals(4, f.check.editorLine)
        assertTrue(f.check.message!!.startsWith("Indent this line by 4 spaces"))
    }

    @Test fun missingColonGetsTheSpecificMessage() {
        val f = g.check("marks = [72, 85, 64]\ntotal = 0\nfor m in marks\n").focus as Focus.Fix
        assertEquals(Verdict.TYPO, f.check.verdict)
        assertTrue(f.check.message!!.startsWith("Add a colon"))
    }

    @Test fun smallTypoPointsAtTheSpot() {
        val f = g.check("marks = [72, 85, 64]\ntotl = 0\n").focus as Focus.Fix
        assertEquals(Verdict.TYPO, f.check.verdict)
        assertTrue(f.check.message!!, f.check.message!!.startsWith("Almost."))
    }

    @Test fun unrelatedLineIsDifferent() {
        val f = g.check("print('hello world')\n").focus as Focus.Fix
        assertEquals(Verdict.DIFFERENT, f.check.verdict)
    }

    @Test fun commonNoviceMistakes() {
        val odd = GuidedTyping(programs.single { it.id == "even-or-odd" })
        val start = "number = int(input(\"Enter a number: \"))\n"
        assertTrue((odd.check(start + "if number % 2 = 0:\n").focus as Focus.Fix).check.message!!.startsWith("Use == to compare"))
        assertTrue((odd.check("number = int(input(\"Enter a number: \")\n").focus as Focus.Fix).check.message!!.contains("bracket"))
        assertTrue((odd.check("Number = int(input(\"Enter a number: \"))\n").focus as Focus.Fix).check.verdict == Verdict.TYPO)
    }

    @Test fun blankLinesAndCommentsAreIgnored() {
        val r = g.check("# my program\nmarks = [72, 85, 64]\n\n\ntotal = 0\n")
        assertEquals(2, r.linesDone)
        assertEquals(5, r.checks[1].editorLine)
    }

    @Test fun completeProgramIsDoneAndExtraLinesAreFlagged() {
        assertEquals(Focus.Done, g.check(total.source).focus)
        assertTrue(g.check(total.source + "print('bye')\n").focus is Focus.Extra)
    }

    @Test fun thirdHintRevealsTheBookLine() = assertEquals("for m in marks:", g.targetText(3))

    @Test fun tabsCountAsFourSpaces() {
        val r = g.check("marks = [72, 85, 64]\ntotal = 0\nfor m in marks:\n\ttotal = total + m\n")
        assertTrue(r.checks.all { it.ok })
    }

    @Test fun normalisation() {
        assertEquals(GuidedTyping.norm("print( 'a b' , x )"), GuidedTyping.norm("print(\"a b\", x)"))
        assertTrue(GuidedTyping.norm("print('a b')") != GuidedTyping.norm("print('ab')"))  // spaces inside text matter
        assertEquals(1, GuidedTyping.distance("totl", "total"))
    }
}
