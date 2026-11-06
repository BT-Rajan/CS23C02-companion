package app.cs23c02.companion.api

/**
 * Every failure the UI can see. [message] is always safe to show: either the server's own student-facing message
 * (backend/app/errors.py never echoes input) or one of ours. [status] 0 means the server could not be reached.
 */
class ApiError(
    val status: Int,
    val code: String,
    override val message: String,
    val ref: String? = null,
    val retryAfterSeconds: Int? = null,
    val fields: List<String> = emptyList(),
) : Exception(message) {
    val isNetwork get() = status == 0
    val isUnauthenticated get() = status == 401

    companion object {
        const val NETWORK_MESSAGE = "Can't reach the server. Check your connection and try again."
        fun network() = ApiError(0, "network", NETWORK_MESSAGE)
        fun signedOut() = ApiError(401, "unauthenticated", "Please sign in again.")
    }
}
