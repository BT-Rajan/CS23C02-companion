package app.cs23c02.companion.stepper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Uses the real demo asset produced by runtime/demos/build_demos.py (so this also checks the app parses the harness output). */
class StepperStateTest {
    private val programs = Recordings.parsePrograms(File("src/main/assets/stepper/demos.json").readText())
    private fun program(id: String) = programs.single { it.id == id }

    @Test fun allDemosParseAndFinish() {
        assertEquals(listOf("running-total", "function-call", "recursion", "even-or-odd"), programs.map { it.id })
        programs.forEach { assertEquals("ok", it.recording.status) }
    }

    @Test fun startsBeforeTheFirstLine() {
        val s = StepperState(program("running-total"))
        assertTrue(s.atStart); assertNull(s.currentLine)
        assertEquals("Press Step to run the first line.", s.narration)
    }

    @Test fun stepForwardAndBackIsExact() {
        var s = StepperState(program("running-total"))
        repeat(4) { s = s.next() }
        val snapshot = s.frames to s.currentLine
        s = s.next().next().back().back()
        assertEquals(snapshot, s.frames to s.currentLine)
    }

    @Test fun loopShowsChangesAndNarration() {
        var s = StepperState(program("running-total")).next().next().next()   // after "total = 0", before "for"
        assertEquals(3, s.currentLine)
        assertEquals(setOf("total"), s.changed)
        assertEquals("Line 3 runs next. total is now 0.", s.narration)
        assertEquals("Each time round the loop, m takes the next mark from the list.", s.note)
        s = s.next()
        assertEquals("Line 4 runs next. m is now 72.", s.narration)
        assertEquals(3, s.previousLine)
    }

    @Test fun outputGrowsAsPrintsRun() {
        val p = program("running-total")
        val s = StepperState(p)
        assertEquals("", s.outputSoFar)
        assertEquals(p.recording.stdout, s.seek(s.total).outputSoFar)
        assertEquals("Total: 221\nAverage: 73.7\n", p.recording.stdout)
    }

    @Test fun functionCallShowsItsOwnFrameAndReturn() {
        var s = StepperState(program("function-call"))
        while (s.frames.size < 2) s = s.next()
        assertEquals(listOf("main program", "area"), s.frames.map { it.name })
        assertTrue(s.narration.startsWith("Calling area."))
        assertEquals(mapOf("width" to "4", "height" to "3"), s.frames.last().vars.mapValues { it.value.r })
        while (s.step?.event != "return") s = s.next()
        assertEquals("area returns 12.", s.narration)
    }

    @Test fun recursionStacksFourCalls() {
        var s = StepperState(program("recursion"))
        var deepest = 0
        while (!s.atEnd) { s = s.next(); deepest = maxOf(deepest, s.frames.size) }
        assertEquals(5, deepest)
        assertEquals("Program finished.", s.ending)
        assertEquals("The program has finished.", s.narration)
    }

    @Test fun seekIsClamped() {
        val s = StepperState(program("recursion"))
        assertEquals(0, s.seek(-5).position)
        assertEquals(s.total, s.seek(10_000).position)
    }

    @Test fun endingExplainsLimitsAndErrors() {
        val base = program("recursion")
        fun with(status: String, err: RunError? = null) =
            StepperState(base.copy(recording = base.recording.copy(status = status, error = err))).seek(10_000)
        assertTrue(with("step_limit").ending!!.contains("more than"))
        assertEquals("Stopped with an error on line 3: ZeroDivisionError: division by zero",
            with("error", RunError("ZeroDivisionError", "division by zero", 3)).ending)
    }
}
