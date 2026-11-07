package app.cs23c02.companion.stepper

/**
 * Pure replay logic over a recording (no Android types, fully unit-tested). Position 0 is "before the first line";
 * position i (1..n) shows step i-1. Stepping back is exact because nothing is re-executed.
 */
data class StepperState(val program: StepProgram, val position: Int = 0) {
    private val steps get() = program.recording.steps
    val total: Int get() = steps.size
    val atStart: Boolean get() = position == 0
    val atEnd: Boolean get() = position >= total
    val step: Step? get() = if (position == 0) null else steps[position - 1]
    private val previous: Step? get() = if (position <= 1) null else steps[position - 2]

    fun next() = copy(position = (position + 1).coerceAtMost(total))
    fun back() = copy(position = (position - 1).coerceAtLeast(0))
    fun restart() = copy(position = 0)
    fun seek(p: Int) = copy(position = p.coerceIn(0, total))

    /** The line about to run (highlighted). On a return step it is the line the function returns from. */
    val currentLine: Int? get() = step?.line
    /** The line that just ran (faint marker), if different from the current one. */
    val previousLine: Int? get() = previous?.line?.takeIf { it != currentLine }

    val outputSoFar: String get() = program.recording.stdout.take(step?.out ?: 0)
    val frames: List<Frame> get() = step?.stack.orEmpty()
    val changed: Set<String> get() = step?.changed.orEmpty().toSet()
    val note: String? get() = currentLine?.let { program.stepNotes[it.toString()] }

    /** One plain sentence describing this step; shown on screen and announced by TalkBack. */
    val narration: String get() {
        val s = step ?: return "Press Step to run the first line."
        val top = s.stack.lastOrNull()
        val changes = s.changed.mapNotNull { name -> top?.vars?.get(name)?.let { "$name is now ${it.r}" } }
        val what = when (s.event) {
            "return" -> if (s.value != null) "${top?.name ?: "The function"} returns ${s.value.r}." else "The program has finished."
            "exception" -> "${s.exc?.type ?: "An error"} raised on line ${s.line}: ${s.exc?.message.orEmpty()}"
            else -> "Line ${s.line} runs next."
        }
        val entered = previous?.let { p -> s.stack.size > p.stack.size && s.event == "line" } == true
        val prefix = if (entered) "Calling ${top?.name}. " else ""
        return listOfNotNull(prefix + what, changes.takeIf { it.isNotEmpty() }?.joinToString(", ", postfix = ".")).joinToString(" ")
    }

    /** Shown when the last step has been reached. */
    val ending: String? get() {
        if (!atEnd) return null
        val r = program.recording
        return when (r.status) {
            "ok" -> "Program finished."
            "error", "syntax_error" -> "Stopped with an error on line ${r.error?.line ?: "?"}: ${r.error?.type}: ${r.error?.message}"
            "step_limit" -> "This program ran more than ${total} steps. Step through a smaller input."
            "time_limit" -> "This program took too long, so it was stopped."
            "output_limit" -> "This program printed too much, so it was stopped."
            "needs_input" -> "The program is waiting for input."
            else -> "Stopped."
        }
    }
}
