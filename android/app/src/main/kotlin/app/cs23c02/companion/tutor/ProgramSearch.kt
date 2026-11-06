package app.cs23c02.companion.tutor

import app.cs23c02.companion.stepper.StepProgram

/**
 * "Which program do you want?" Matches the student's own words against book program titles and keywords, offline.
 * "program to find factorial" → Factorial; "even odd" → Even or odd. Filler words are ignored.
 */
object ProgramSearch {
    private val FILLER = setOf("a", "an", "the", "to", "of", "for", "in", "on", "and", "or", "is", "me", "my", "i", "want",
        "show", "write", "program", "programs", "code", "python", "how", "do", "find", "make", "please", "with", "using", "that")

    fun words(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() && it !in FILLER }

    fun search(query: String, programs: List<StepProgram>): List<StepProgram> {
        val q = words(query)
        if (q.isEmpty()) return programs
        return programs.map { it to score(q, it) }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    private fun score(q: List<String>, p: StepProgram): Int {
        val title = words(p.title).toSet()
        val keys = p.keywords.flatMap { words(it) }.toSet()
        return q.map { w ->
            when {
                w in title -> 3
                w in keys -> 2
                w.length >= 4 && (title + keys).any { it.startsWith(w) || (w.startsWith(it) && it.length >= 4) } -> 1
                else -> 0
            }
        }.sum()
    }
}
