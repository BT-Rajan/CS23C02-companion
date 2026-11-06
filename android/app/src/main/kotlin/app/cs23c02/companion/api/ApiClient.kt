package app.cs23c02.companion.api

import app.cs23c02.companion.auth.TokenStore
import app.cs23c02.companion.auth.Tokens
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.CertificatePinner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The only way the app talks to the server. Identity always comes from the bearer token, never from parameters.
 * Authenticated calls refresh the access token once on expiry or 401 (single-flight, so parallel calls never
 * race and trip the server's refresh-reuse detection); if refresh fails the stored tokens are wiped.
 */
class ApiClient(
    baseUrl: String,
    private val tokens: TokenStore,
    private val http: OkHttpClient = defaultHttp(baseUrl, emptyList()),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    private val refreshLock = Mutex()

    // ---- Unauthenticated ----------------------------------------------------------------------------------------
    suspend fun register(email: String, password: String) {
        call("POST", "/v1/auth/register", obj("email" to email, "password" to password))
    }

    suspend fun verifyEmail(email: String, code: String, deviceLabel: String) =
        saveTokens(call("POST", "/v1/auth/verify-email", obj("email" to email, "code" to code, "device_label" to deviceLabel)))

    suspend fun login(email: String, password: String, deviceLabel: String) =
        saveTokens(call("POST", "/v1/auth/login", obj("email" to email, "password" to password, "device_label" to deviceLabel)))

    suspend fun forgot(email: String) {
        call("POST", "/v1/auth/forgot", obj("email" to email))
    }

    suspend fun reset(email: String, code: String, newPassword: String) {
        call("POST", "/v1/auth/reset", obj("email" to email, "code" to code, "new_password" to newPassword))
    }

    // ---- Authenticated ------------------------------------------------------------------------------------------
    suspend fun me(): Me = json.decodeFromString(Me.serializer(), authed("GET", "/v1/me", null))

    suspend fun activate(code: String): Entitlement =
        json.decodeFromString(Entitlement.serializer(), authed("POST", "/v1/activation", obj("code" to code)))

    /** Best effort: the local sign-out happens even when the server cannot be reached. */
    suspend fun logout() {
        try {
            authed("POST", "/v1/auth/logout", null)
        } catch (_: ApiError) {
        } finally {
            tokens.clear()
        }
    }

    fun hasSession(): Boolean = tokens.load() != null

    // ---- Content and progress (A2) ------------------------------------------------------------------------------
    /** The exact signed manifest bytes plus the signature header; the caller verifies before trusting anything. */
    suspend fun manifest(edition: String?): SignedManifest {
        val q = edition?.let { "?edition=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        val r = authedRaw("GET", "/v1/content/manifest$q", null)
        val sig = r.headers["X-Manifest-Signature"] ?: throw ApiError(0, "bad_manifest", "The lessons could not be verified.")
        return SignedManifest(r.bytes, sig, r.headers["X-Content-Version"].orEmpty())
    }

    suspend fun contentFile(sha256: String): ByteArray {
        require(Regex("^[0-9a-f]{64}$").matches(sha256))
        return authedRaw("GET", "/v1/content/files/$sha256", null).bytes
    }

    suspend fun progress(edition: String?): ProgressSummary {
        val q = edition?.let { "?edition=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: ""
        return json.decodeFromString(ProgressSummary.serializer(), authed("GET", "/v1/progress$q", null))
    }

    suspend fun putProgress(edition: String?, events: List<ProgressEventOut>): List<EventResult> {
        val body = json.encodeToJsonElement(ProgressBatch.serializer(), ProgressBatch(edition, events))
        return json.decodeFromString(EventResults.serializer(), authedRaw("PUT", "/v1/progress/events", body).bytes.decodeToString()).results
    }

    // ---- Plumbing -----------------------------------------------------------------------------------------------
    private suspend fun authed(method: String, path: String, body: JsonObject?): String =
        authedRaw(method, path, body).bytes.decodeToString()

    private suspend fun authedRaw(method: String, path: String, body: kotlinx.serialization.json.JsonElement?): Raw {
        var current = tokens.load() ?: throw ApiError.signedOut()
        if (clock() >= current.accessExpiresAtMs) current = refresh(current)
        return try {
            exchange(method, path, body, current.access)
        } catch (e: ApiError) {
            if (!e.isUnauthenticated) throw e
            exchange(method, path, body, refresh(current).access)
        }
    }

    /** [seen] is the token set the caller used; if another call already rotated it, reuse the new one. */
    private suspend fun refresh(seen: Tokens): Tokens = refreshLock.withLock {
        val now = tokens.load() ?: throw ApiError.signedOut()
        if (now.refresh != seen.refresh) return@withLock now
        try {
            saveTokens(call("POST", "/v1/auth/refresh", obj("refresh_token" to now.refresh)))
            tokens.load() ?: throw ApiError.signedOut()
        } catch (e: ApiError) {
            if (e.isUnauthenticated) { tokens.clear(); throw ApiError.signedOut() }
            throw e
        }
    }

    private fun saveTokens(body: String) {
        val t = json.decodeFromString(TokenResponse.serializer(), body)
        // Refresh 30 s early so a slow request does not arrive with an expired token.
        tokens.save(Tokens(t.accessToken, t.refreshToken, clock() + (t.expiresIn - 30).coerceAtLeast(0) * 1000))
    }

    private class Raw(val bytes: ByteArray, val headers: okhttp3.Headers)

    private suspend fun call(method: String, path: String, body: JsonObject?, bearer: String? = null): String =
        exchange(method, path, body, bearer).bytes.decodeToString()

    private suspend fun exchange(method: String, path: String, body: kotlinx.serialization.json.JsonElement?, bearer: String? = null): Raw = withContext(io) {
        val rb = when {
            body != null -> body.toString().toRequestBody(JSON)
            method == "GET" -> null
            else -> ByteArray(0).toRequestBody(null)
        }
        val req = Request.Builder().url(base + path).method(method, rb)
            .header("Accept", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes() ?: ByteArray(0)
                if (resp.isSuccessful) return@withContext Raw(bytes, resp.headers)
                throw parseError(resp.code, bytes.decodeToString(), resp.header("Retry-After"))
            }
        } catch (e: IOException) {
            throw ApiError.network()
        }
    }

    private fun parseError(status: Int, text: String, retryAfter: String?): ApiError {
        val d = try { json.decodeFromString(ErrorBody.serializer(), text).error } catch (_: Exception) { null }
        val fallback = if (status >= 500) "Something went wrong on our side. Please try again in a moment." else "Request failed."
        return ApiError(status, d?.code ?: "http_$status", d?.message ?: fallback, d?.ref, retryAfter?.toIntOrNull(), d?.fields.orEmpty())
    }

    private fun obj(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Pins apply to the API host only; an empty list (debug builds without pins) means system trust alone. */
        fun defaultHttp(baseUrl: String, pins: List<String>): OkHttpClient {
            val host = baseUrl.removePrefix("https://").substringBefore('/').substringBefore(':')
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .apply { if (pins.isNotEmpty()) certificatePinner(CertificatePinner.Builder().add(host, *pins.toTypedArray()).build()) }
                .build()
        }
    }
}
