package app.cs23c02.companion.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("reverify_required") val reverifyRequired: Boolean = false,
)

@Serializable
data class Entitlement(
    @SerialName("edition_id") val editionId: String,
    val title: String,
    @SerialName("activated_at") val activatedAt: String,
)

@Serializable
data class Me(
    val email: String,
    val entitlements: List<Entitlement> = emptyList(),
    /** The server saw this account used on many devices or networks (ADR 0004): this device must confirm with an emailed code. */
    @SerialName("reverify_required") val reverifyRequired: Boolean = false,
)

@Serializable
data class ReverifySent(val status: String, @SerialName("email_hint") val emailHint: String? = null)

@Serializable
internal data class ErrorBody(val error: ErrorDetail)

@Serializable
internal data class ErrorDetail(val message: String, val code: String, val ref: String? = null, val fields: List<String>? = null)

class SignedManifest(val bytes: ByteArray, val signature: String, val version: String)

@Serializable
data class ResumePoint(@SerialName("lesson_id") val lessonId: String, val title: String = "", val reason: String = "")

@Serializable
data class ProgressSummary(
    @SerialName("edition_id") val editionId: String,
    @SerialName("content_version") val contentVersion: String = "",
    @SerialName("total_lessons") val totalLessons: Int = 0,
    val completed: List<String> = emptyList(),
    val finished: Boolean = false,
    val resume: ResumePoint? = null,
    /** Exercises passed in the server sandbox (server-verified, ADR 0009). */
    @SerialName("exercises_passed") val exercisesPassed: List<String> = emptyList(),
    /** Best submitted score per unit assessment. */
    val assessments: List<AssessmentBest> = emptyList(),
)

@Serializable
data class AssessmentBest(@SerialName("assessment_id") val assessmentId: String, @SerialName("best_score") val bestScore: Int,
                          @SerialName("max_score") val maxScore: Int? = null, val attempts: Int = 0)

@Serializable
data class AssessmentQuestion(@SerialName("item_id") val itemId: String, val type: String = "mcq", val stem: String,
                              val code: String? = null, val options: List<app.cs23c02.companion.content.ItemOption> = emptyList())

@Serializable
data class ReviewItem(@SerialName("item_id") val itemId: String, val type: String = "mcq", val stem: String, val code: String? = null,
                      val options: List<app.cs23c02.companion.content.ItemOption> = emptyList(), val chosen: String? = null,
                      val answer: String? = null, val correct: Boolean = false, val explanation: String = "", val why: String? = null)

/** One attempt as the server returns it: questions + saved answers while open, score + review once submitted. */
@Serializable
data class AttemptView(
    @SerialName("attempt_id") val attemptId: String,
    @SerialName("assessment_id") val assessmentId: String,
    val title: String = "",
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val seq: Int = 0,
    val answers: Map<String, String> = emptyMap(),
    val questions: List<AssessmentQuestion> = emptyList(),
    @SerialName("submitted_at") val submittedAt: String? = null,
    @SerialName("auto_submitted") val autoSubmitted: Boolean = false,
    val score: Int? = null,
    @SerialName("max_score") val maxScore: Int? = null,
    val review: List<ReviewItem> = emptyList(),
) { val submitted get() = status == "submitted" }

@Serializable
data class AttemptSummary(@SerialName("attempt_id") val attemptId: String, val status: String,
                          @SerialName("started_at") val startedAt: String? = null, @SerialName("submitted_at") val submittedAt: String? = null,
                          val score: Int? = null, @SerialName("max_score") val maxScore: Int? = null,
                          @SerialName("auto_submitted") val autoSubmitted: Boolean = false)

@Serializable
data class AssessmentHistory(@SerialName("assessment_id") val assessmentId: String,
                             @SerialName("next_attempt_at") val nextAttemptAt: String? = null,
                             val attempts: List<AttemptSummary> = emptyList())

/** Only what the server accepts from a client: an id it can dedupe on, a low-stakes kind, the lesson, and a timestamp. */
@Serializable
data class ProgressEventOut(val id: String, val kind: String, @SerialName("content_id") val contentId: String,
                            @SerialName("client_ts") val clientTs: String? = null)

@Serializable
data class ProgressBatch(val edition: String?, val events: List<ProgressEventOut>)

@Serializable data class EventResult(val id: String, val status: String)
@Serializable data class EventResults(val results: List<EventResult>)

/** The server's verdict for ONE practice question (backend/app/practice.py). [answer]/[explanation] only once revealed. */
@Serializable
data class PracticeVerdict(
    @SerialName("item_id") val itemId: String,
    val correct: Boolean,
    @SerialName("attempts_wrong") val attemptsWrong: Int = 0,
    val why: String? = null,
    val revealed: Boolean = false,
    val answer: String? = null,
    val explanation: String? = null,
)

@Serializable
data class TestOutcome(val stdin: String, val expect: String, val got: String = "", val passed: Boolean,
                       val status: String = "ok", val error: String? = null)

@Serializable
data class HiddenSummary(val passed: Int, val total: Int, @SerialName("first_failure") val firstFailure: String? = null)

/** Where the hint/solution policy stands for one exercise (backend/app/exercises.py). */
@Serializable
data class HelpState(
    @SerialName("failed_checks") val failedChecks: Int = 0,
    val passed: Boolean = false,
    @SerialName("hints_unlocked") val hintsUnlocked: Int = 0,
    @SerialName("hints_total") val hintsTotal: Int = 0,
    @SerialName("next_hint_in") val nextHintIn: Int? = null,
    @SerialName("solution_unlocked") val solutionUnlocked: Boolean = false,
    @SerialName("solution_in") val solutionIn: Int? = null,
)

@Serializable
data class ExerciseResult(@SerialName("exercise_id") val exerciseId: String, val passed: Boolean,
                          val public: List<TestOutcome> = emptyList(), val hidden: HiddenSummary = HiddenSummary(0, 0),
                          val help: HelpState = HelpState())

@Serializable
data class Solution(val approach: String = "", val code: String = "")

@Serializable
data class ExerciseHelp(
    @SerialName("failed_checks") val failedChecks: Int = 0,
    val passed: Boolean = false,
    @SerialName("hints_unlocked") val hintsUnlocked: Int = 0,
    @SerialName("hints_total") val hintsTotal: Int = 0,
    @SerialName("next_hint_in") val nextHintIn: Int? = null,
    @SerialName("solution_unlocked") val solutionUnlocked: Boolean = false,
    @SerialName("solution_in") val solutionIn: Int? = null,
    val hints: List<String> = emptyList(),
    val solution: Solution? = null,
)
