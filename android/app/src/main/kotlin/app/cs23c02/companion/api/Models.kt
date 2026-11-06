package app.cs23c02.companion.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class Entitlement(
    @SerialName("edition_id") val editionId: String,
    val title: String,
    @SerialName("activated_at") val activatedAt: String,
)

@Serializable
data class Me(val email: String, val entitlements: List<Entitlement> = emptyList())

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
)

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
