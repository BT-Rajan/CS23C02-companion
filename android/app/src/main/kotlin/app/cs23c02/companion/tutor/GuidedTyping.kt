package app.cs23c02.companion.tutor

import app.cs23c02.companion.stepper.LineInfo
import app.cs23c02.companion.stepper.StepProgram

/** How one typed line compares with the book's line (ADR 0014 §3). */
enum class Verdict { CORRECT, EQUIVALENT, INDENT, TYPO, DIFFERENT }

data class LineCheck(val editorLine: Int, val targetLine: Int, val verdict: Verdict, val message: String?) {
    val ok get() = verdict == Verdict.CORRECT || verdict == Verdict.EQUIVALENT
}

sealed interface Focus {
    /** A typed line needs attention before going on. */
    data class Fix(val check: LineCheck, val target: LineInfo?) : Focus
    /** The student is part-way through a line that so far matches. */
    data class Typing(val target: LineInfo?, val targetLine: Int) : Focus
    /** Everything typed is right; this is the next line the book's program needs. */
    data class Next(val target: LineInfo?, val targetLine: Int) : Focus
    /** More lines than the book's program. */
    data class Extra(val editorLine: Int) : Focus
    data object Done : Focus
}

data class Guidance(val checks: List<LineCheck>, val focus: Focus, val linesDone: Int, val linesTotal: Int)

/**
 * The tutor that "travels with" a student typing a book program. Pure and fast (runs on every keystroke): it compares
 * each typed line with the book's line in order, ignoring blank lines and comments, and decides what to say next.
 */
class GuidedTyping(private val program: StepProgram) {
    private val target: List<Pair<Int, String>> = program.source.lines().withIndex()
        .filter { (_, t) -> t.isNotBlank() && !t.trimStart().startsWith("#") }
        .map { (i, t) -> (i + 1) to t.trimEnd() }

    val targetLines: Int get() = target.size

    /** [typed] is the whole editor text. A last line without a trailing newline is "still being typed". */
    fun check(typed: String): Guidance {
        val raw = typed.replace("\t", "    ").split("\n")
        val inProgressIndex = if (typed.endsWith("\n")) -1 else raw.lastIndex
        val entries = raw.withIndex().filter { (_, t) -> t.isNotBlank() && !t.trimStart().startsWith("#") }
        val checks = mutableListOf<LineCheck>()
        for ((k, e) in entries.withIndex()) {
            val (i, text) = e
            if (k >= target.size) return Guidance(checks, Focus.Extra(i + 1), checks.count { it.ok }, target.size)
            val (tn, tText) = target[k]
            if (i == inProgressIndex && isPrefix(text, tText) && norm(text) != norm(tText)) {
                return Guidance(checks, Focus.Typing(program.lineInfo(tn), tn), checks.count { it.ok }, target.size)
            }
            val c = compare(i + 1, tn, text.trimEnd(), tText)
            checks += c
            if (!c.ok) return Guidance(checks, Focus.Fix(c, program.lineInfo(tn)), checks.count { it.ok }, target.size)
        }
        val done = checks.size
        val focus = if (done >= target.size) Focus.Done else Focus.Next(program.lineInfo(target[done].first), target[done].first)
        return Guidance(checks, focus, done, target.size)
    }

    /** The book's line text, used by the third hint level ("show the line"). */
    fun targetText(targetLine: Int): String = target.firstOrNull { it.first == targetLine }?.second.orEmpty()

    private fun compare(editorLine: Int, tn: Int, typed: String, want: String): LineCheck {
        if (typed == want) return LineCheck(editorLine, tn, Verdict.CORRECT, null)
        val ti = indentOf(typed)
        val wi = indentOf(want)
        val same = norm(typed) == norm(want)
        if (same && ti == wi) return LineCheck(editorLine, tn, Verdict.EQUIVALENT,
            "Correct. The book spaces it as: ${want.trim()}")
        if (same) {
            val msg = if (ti < wi) "Indent this line by ${wi} spaces: it belongs inside the block above (the line that ends with ':')."
            else if (wi == 0) "Remove the spaces at the start: this line is not inside a block."
            else "Use ${wi} spaces at the start of this line, not ${ti}."
            return LineCheck(editorLine, tn, Verdict.INDENT, msg)
        }
        val a = norm(typed)
        val b = norm(want)
        val close = distance(a, b) <= maxOf(2, b.length / 4)
        val specific = commonMistake(typed.trim(), want.trim())
        if (close || specific != null) {
            val col = typed.trim().zip(want.trim()).indexOfFirst { (x, y) -> x != y }.let { if (it < 0) minOf(typed.trim().length, want.trim().length) else it }
            val near = typed.trim().drop(maxOf(0, col - 3)).take(10)
            return LineCheck(editorLine, tn, Verdict.TYPO, specific ?: "Almost. Look again near \"$near\".")
        }
        return LineCheck(editorLine, tn, Verdict.DIFFERENT, specific
            ?: "This is not what the book's line $tn does. Read the explanation, or tap Hint.")
    }

    private fun commonMistake(t: String, w: String): String? = when {
        w.endsWith(":") && !t.endsWith(":") && norm(t + ":") == norm(w) ->
            "Add a colon ':' at the end. Lines that start a block (for, if, while, def, else) end with ':'."
        t.count { it == '(' } != t.count { it == ')' } -> "A bracket is missing: every ( needs a matching )."
        t.count { it == '[' } != t.count { it == ']' } -> "A square bracket is missing: every [ needs a matching ]."
        t.count { it == '"' } % 2 == 1 || t.count { it == '\'' } % 2 == 1 -> "A quote mark is missing: text starts and ends with a quote."
        Regex("^(if|elif|while)\\b[^=!<>]*[^=!<>]=[^=]").containsMatchIn(t) && w.contains("==") ->
            "Use == to compare two values. A single = stores a value."
        t.isNotEmpty() && w.isNotEmpty() && t[0].isLetter() && w.startsWith(t.split(" ")[0].lowercase()) &&
            t.split(" ")[0] != t.split(" ")[0].lowercase() -> "Python keywords and names are case-sensitive: write it in lower case."
        else -> null
    }

    companion object {
        fun indentOf(s: String) = s.length - s.trimStart().length

        /** Removes spaces outside string literals and treats ' and " alike, so only meaningful differences remain. */
        fun norm(s: String): String {
            val out = StringBuilder()
            var quote: Char? = null
            for (ch in s.trim()) {
                when {
                    quote != null -> { out.append(if (ch == '\'') '"' else ch); if (ch == quote) quote = null }
                    ch == '"' || ch == '\'' -> { quote = ch; out.append('"') }
                    ch.isWhitespace() -> Unit
                    else -> out.append(ch)
                }
            }
            return out.toString()
        }

        fun isPrefix(typed: String, want: String): Boolean {
            if (typed.isBlank()) return true
            if (indentOf(typed) != indentOf(want) && typed.trimStart().isNotEmpty()) {
                // Indentation is still being typed: accept fewer spaces only if nothing else is there yet.
                if (indentOf(typed) > indentOf(want)) return false
                if (typed.trim().isNotEmpty() && indentOf(typed) < indentOf(want)) return false
            }
            return norm(want).startsWith(norm(typed))
        }

        fun distance(a: String, b: String): Int {
            val d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = d[0]; d[0] = i
                for (j in 1..b.length) {
                    val tmp = d[j]
                    d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return d[b.length]
        }
    }
}
