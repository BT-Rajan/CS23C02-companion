package app.cs23c02.companion.content

import app.cs23c02.companion.api.AssessmentBest
import app.cs23c02.companion.api.AssessmentHistory
import app.cs23c02.companion.api.AssessmentQuestion
import app.cs23c02.companion.api.AttemptSummary
import app.cs23c02.companion.api.AttemptView
import app.cs23c02.companion.api.ReviewItem
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

/**
 * Debug SAMPLE assessments only: runs on the phone with the SAME rules as backend/app/assessments.py (per-attempt shuffle,
 * one attempt per 24 h, 3-hour expiry with saved answers scored, idempotent submit, review afterwards), so the screens can be
 * tried without a server. Book assessments are always run by the server.
 */
class SampleAssessmentEngine(
    private val pkg: () -> InstalledPackage?,
    private val keys: Map<String, SampleKey>,
    private val clock: () -> Instant = Instant::now,
) {
    private class Att(val id: String, val asm: String, val layout: List<Pair<String, List<String>>>, val started: Instant,
                      var answers: Map<String, String> = emptyMap(), var seq: Int = 0, var submitted: Instant? = null,
                      var auto: Boolean = false, var score: Int = 0, var review: List<ReviewItem> = emptyList())
    private val attempts = mutableListOf<Att>()

    private fun item(id: String) = pkg()?.item(id) ?: throw ContentError("This assessment isn't available.")

    private fun expireIfDue(a: Att) {
        if (a.submitted == null && clock().isAfter(a.started.plus(SESSION).plus(GRACE))) finalize(a, auto = true)
    }

    private fun finalize(a: Att, auto: Boolean) {
        a.review = a.layout.map { (id, opts) ->
            val it = item(id)
            val k = keys[id]
            val chosen = a.answers[id]
            val right = chosen != null && chosen == k?.answer
            ReviewItem(id, it.type, it.stem, it.code, opts.map { o -> it.options.first { x -> x.id == o } }, chosen, k?.answer, right,
                k?.explanation.orEmpty(), if (right || chosen == null) null else k?.optionExplanations?.get(chosen))
        }
        a.score = a.review.count { it.correct }
        a.submitted = clock()
        a.auto = auto
    }

    private fun view(a: Att): AttemptView {
        val title = pkg()?.assessment(a.asm)?.title.orEmpty()
        val base = AttemptView(a.id, a.asm, title, if (a.submitted == null) "in_progress" else "submitted",
            a.started.toString(), a.started.plus(SESSION).toString())
        return if (a.submitted == null) base.copy(seq = a.seq, answers = a.answers, questions = a.layout.map { (id, opts) ->
            val it = item(id)
            AssessmentQuestion(id, it.type, it.stem, it.code, opts.map { o -> it.options.first { x -> x.id == o } })
        }) else base.copy(submittedAt = a.submitted.toString(), autoSubmitted = a.auto, score = a.score, maxScore = a.layout.size,
            review = a.review)
    }

    fun start(asmId: String): AttemptView {
        val asm = pkg()?.assessment(asmId)?.takeIf { it.purpose == "unit_assessment" } ?: throw ContentError("This assessment isn't available.")
        val mine = attempts.filter { it.asm == asmId }.onEach(::expireIfDue)
        mine.firstOrNull { it.submitted == null }?.let { return view(it) }
        val last = mine.mapNotNull { it.submitted }.maxOrNull()
        if (last != null && clock().isBefore(last.plus(COOLDOWN))) throw ContentError("You can take this assessment again 24 hours after your last attempt.")
        val rng = Random(clock().toEpochMilli())
        val layout = asm.items.shuffled(rng).map { id -> id to item(id).options.map { it.id }.shuffled(rng) }
        return view(Att(UUID.randomUUID().toString(), asmId, layout, clock()).also { attempts += it })
    }

    fun history(asmId: String): AssessmentHistory {
        val mine = attempts.filter { it.asm == asmId }.onEach(::expireIfDue)
        val last = mine.mapNotNull { it.submitted }.maxOrNull()
        val next = if (last == null || mine.any { it.submitted == null }) null else last.plus(COOLDOWN).takeIf { it.isAfter(clock()) }
        return AssessmentHistory(asmId, next?.toString(), mine.map {
            AttemptSummary(it.id, if (it.submitted == null) "in_progress" else "submitted", it.started.toString(), it.submitted?.toString(),
                it.submitted?.let { _ -> it.score }, it.submitted?.let { _ -> it.layout.size }, it.auto)
        })
    }

    fun attempt(id: String): AttemptView = view(attempts.firstOrNull { it.id == id }?.also(::expireIfDue) ?: throw ContentError("Attempt not found."))

    fun save(id: String, seq: Int, answers: Map<String, String>) {
        val a = attempts.firstOrNull { it.id == id }?.also(::expireIfDue) ?: throw ContentError("Attempt not found.")
        if (a.submitted != null) throw ContentError("This attempt has already been submitted.")
        if (seq > a.seq) { a.answers = answers; a.seq = seq }
    }

    fun submit(id: String, seq: Int, answers: Map<String, String>): AttemptView {
        val a = attempts.firstOrNull { it.id == id } ?: throw ContentError("Attempt not found.")
        if (a.submitted == null) {
            val late = clock().isAfter(a.started.plus(SESSION).plus(GRACE))
            if (!late && seq > a.seq) { a.answers = answers; a.seq = seq }
            finalize(a, auto = late)
        }
        return view(a)
    }

    fun best(): List<AssessmentBest> = attempts.filter { it.submitted != null }.groupBy { it.asm }.map { (asm, xs) ->
        AssessmentBest(asm, xs.maxOf { it.score }, xs.first().layout.size, xs.size)
    }.sortedBy { it.assessmentId }

    companion object {
        val SESSION: Duration = Duration.ofHours(3)
        val COOLDOWN: Duration = Duration.ofHours(24)
        val GRACE: Duration = Duration.ofMinutes(5)
    }
}
