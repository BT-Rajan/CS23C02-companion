package app.cs23c02.companion.content

import app.cs23c02.companion.api.ExerciseHelp
import app.cs23c02.companion.api.ExerciseResult
import app.cs23c02.companion.api.HelpState
import app.cs23c02.companion.api.HiddenSummary
import app.cs23c02.companion.api.Solution
import app.cs23c02.companion.api.TestOutcome

/**
 * Debug SAMPLE exercises only: graded on the phone (on-device Python) with the SAME policy as backend/app/exercises.py,
 * so the screens can be tried without a server. Book exercises are always graded in the server sandbox.
 */
class SampleExerciseGrader(private val keys: Map<String, SampleExerciseKey>, private val run: suspend (code: String, stdin: String) -> RunOutput) {
    data class RunOutput(val status: String, val stdout: String, val error: String?)
    private val failed = HashMap<String, Int>()
    private val passed = HashSet<String>()

    suspend fun submit(ex: Exercise, code: String): ExerciseResult {
        val k = keys[ex.id] ?: throw ContentError("This exercise isn't available.")
        val pub = ex.tests.map { t -> outcome(t, run(code, t.stdin)) }
        val hid = k.hiddenTests.map { t -> outcome(t, run(code, t.stdin)) }
        val ok = (pub + hid).all { it.passed }
        if (ok) passed += ex.id else failed[ex.id] = (failed[ex.id] ?: 0) + 1
        val firstHidden = hid.firstOrNull { !it.passed }
        return ExerciseResult(ex.id, ok, pub, HiddenSummary(hid.count { it.passed }, hid.size, firstHidden?.let { kind(it.status) }),
            state(ex.id, k.hints.size))
    }

    fun help(ex: Exercise): ExerciseHelp {
        val k = keys[ex.id] ?: throw ContentError("This exercise isn't available.")
        val st = state(ex.id, k.hints.size)
        return ExerciseHelp(st.failedChecks, st.passed, st.hintsUnlocked, st.hintsTotal, st.nextHintIn, st.solutionUnlocked, st.solutionIn,
            k.hints.take(st.hintsUnlocked), if (st.solutionUnlocked) Solution(k.solutionApproach, k.referenceSolution) else null)
    }

    private fun state(id: String, nHints: Int): HelpState {
        val f = failed[id] ?: 0
        val p = id in passed
        val thresholds = HINT_AFTER + (1..nHints).map { HINT_AFTER.last() + it }
        val unlocked = if (p) nHints else thresholds.take(nHints).count { f >= it }
        val next = if (unlocked >= nHints) null else thresholds[unlocked] - f
        val sol = p || f >= SOLUTION_AFTER
        return HelpState(f, p, unlocked, nHints, next, sol, if (sol) null else SOLUTION_AFTER - f)
    }

    private fun outcome(t: PublicTest, r: RunOutput) =
        TestOutcome(t.stdin, t.expect, r.stdout, r.status == "ok" && sameOutput(r.stdout, t.expect), r.status, r.error)

    private fun kind(status: String) = when (status) {
        "ok" -> "wrong output"; "timeout" -> "ran too long"; "memory" -> "used too much memory"
        "output_limit" -> "printed too much"; "error" -> "stopped with an error"; else -> "did not run"
    }

    companion object {
        val HINT_AFTER = listOf(1, 3)
        const val SOLUTION_AFTER = 5

        /** Same rule as sandbox/grader.py: trailing spaces and trailing blank lines don't count. */
        fun sameOutput(got: String, want: String): Boolean {
            fun norm(s: String) = s.replace("\r\n", "\n").split("\n").map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
            return norm(got) == norm(want)
        }
    }
}
