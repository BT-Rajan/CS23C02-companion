package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.api.ApiError
import app.cs23c02.companion.api.ProgressEventOut
import app.cs23c02.companion.api.ProgressSummary
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.util.UUID

@Serializable
data class LocalProgress(
    /** Not yet accepted by the server. Each has a client UUID, so re-sending is safe (ADR 0009). */
    val pending: List<ProgressEventOut> = emptyList(),
    val completed: Set<String> = emptySet(),
    val lastOpened: String? = null,
    /** Scroll position per lesson, so Continue opens exactly where the student stopped (journey J2). Local only. */
    val positions: Map<String, Int> = emptyMap(),
)

/**
 * Lesson progress on the phone: low-stakes, client-reported events (ADR 0009) kept in one small JSON file and sent in batches.
 * Completion never goes backwards: local and server sets are merged by union.
 */
class ProgressStore(private val file: File, private val clock: () -> Instant = Instant::now) {
    @Volatile var state: LocalProgress = load()
        private set

    private fun load(): LocalProgress = try {
        if (file.exists()) ContentJson.decodeFromString(LocalProgress.serializer(), file.readText()) else LocalProgress()
    } catch (e: Exception) { LocalProgress() }

    @Synchronized private fun save(p: LocalProgress) {
        state = p
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(ContentJson.encodeToString(LocalProgress.serializer(), p))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    private fun event(kind: String, lessonId: String) = ProgressEventOut(UUID.randomUUID().toString(), kind, lessonId, clock().toString())

    @Synchronized fun opened(lessonId: String) {
        val s = state
        if (s.lastOpened == lessonId && s.pending.any { it.kind == "lesson_opened" && it.contentId == lessonId }) {
            return  // already queued: re-opening the same lesson does not pile up events
        }
        save(s.copy(lastOpened = lessonId, pending = (s.pending + event("lesson_opened", lessonId)).takeLast(MAX_PENDING)))
    }

    @Synchronized fun completed(lessonId: String) {
        val s = state
        if (lessonId in s.completed) return
        save(s.copy(completed = s.completed + lessonId, pending = (s.pending + event("lesson_completed", lessonId)).takeLast(MAX_PENDING)))
    }

    @Synchronized fun position(lessonId: String, scroll: Int) {
        if (state.positions[lessonId] == scroll) return
        save(state.copy(positions = state.positions + (lessonId to scroll)))
    }

    /** Sends queued events (up to 100 per request). Accepted, duplicate and unknown-lesson events leave the queue. */
    suspend fun flush(api: ApiClient, edition: String?) {
        while (state.pending.isNotEmpty()) {
            val batch = state.pending.take(100)
            val results = api.putProgress(edition, batch)  // throws on network/auth errors: the queue is kept
            val done = results.map { it.id }.toSet()
            synchronized(this) { save(state.copy(pending = state.pending.filterNot { it.id in done })) }
            if (done.isEmpty()) break
        }
    }

    /** Server-verified history (e.g. after reinstall or on a new phone) joins the local one. */
    @Synchronized fun merge(server: ProgressSummary) {
        val s = state
        val lastOpened = s.lastOpened ?: server.resume?.takeIf { it.reason == "in_progress" }?.lessonId
        save(s.copy(completed = s.completed + server.completed, lastOpened = lastOpened))
    }

    fun clear() { file.delete(); state = LocalProgress() }

    companion object {
        const val MAX_PENDING = 500

        /** Same rule as the server's GET /v1/progress: the open, unfinished lesson; else the next after the furthest done. */
        fun resume(lessonIds: List<String>, completed: Set<String>, lastOpened: String?): String? {
            if (lastOpened != null && lastOpened in lessonIds && lastOpened !in completed) return lastOpened
            val top = lessonIds.indexOfLast { it in completed }
            return lessonIds.drop(top + 1).firstOrNull { it !in completed } ?: lessonIds.firstOrNull { it !in completed }
        }
    }
}

/** Keeps lessons and progress up to date. Offline is normal: whatever is installed keeps working. */
class ReaderRepository(
    private val api: ApiClient,
    val content: ContentStore,
    val progress: ProgressStore,
    /** Debug-only SAMPLE package bundled in the APK; when set, nothing is sent to or fetched from the server. */
    private val samplePackage: (suspend () -> Pair<ByteArray, Map<String, ByteArray>>)? = null,
    private val sampleChecker: SamplePracticeChecker? = null,
) {
    /** One practice answer: checked by the server (or, for debug SAMPLE questions only, locally). Never recorded as progress. */
    suspend fun checkPractice(itemId: String, option: String, edition: String?): app.cs23c02.companion.api.PracticeVerdict =
        sampleChecker?.check(itemId, option) ?: api.answerPractice(itemId, option, edition)

    val isSample get() = samplePackage != null

    sealed interface SyncOutcome {
        data object UpToDate : SyncOutcome
        data object Updated : SyncOutcome
        data class Offline(val hasContent: Boolean) : SyncOutcome
        data class Failed(val message: String) : SyncOutcome
    }

    suspend fun sync(edition: String?): SyncOutcome {
        samplePackage?.let { load ->
            if (content.current() != null) return SyncOutcome.UpToDate
            return try {
                val (manifestAndSig, files) = load()
                val nl = manifestAndSig.indexOf('\n'.code.toByte())
                content.install(manifestAndSig.copyOfRange(nl + 1, manifestAndSig.size), manifestAndSig.copyOfRange(0, nl).decodeToString()) { e ->
                    files[e.path] ?: throw ContentError("A lesson file is missing.")
                }
                SyncOutcome.Updated
            } catch (e: ContentError) { SyncOutcome.Failed(e.message ?: "The sample lessons could not be loaded.") }
        }
        val outcome = try {
            val signed = api.manifest(edition)
            if (signed.version.isNotEmpty() && signed.version == content.currentVersion()) SyncOutcome.UpToDate
            else { content.install(signed.bytes, signed.signature) { e -> api.contentFile(e.sha256) }; SyncOutcome.Updated }
        } catch (e: ApiError) {
            if (e.isNetwork) SyncOutcome.Offline(content.current() != null)
            else if (e.code == "no_content") SyncOutcome.Failed("Lessons for your book are not available yet.")
            else SyncOutcome.Failed(e.message)
        } catch (e: ContentError) {
            SyncOutcome.Failed(e.message ?: "The lessons could not be verified.")
        }
        if (outcome !is SyncOutcome.Offline) {
            try {
                progress.flush(api, edition)
                progress.merge(api.progress(edition))
            } catch (_: ApiError) { /* progress stays queued; reading works offline */ }
        }
        return outcome
    }

    /** Fire-and-forget after an event; failures keep the queue for the next sync. */
    suspend fun pushProgress(edition: String?) {
        if (isSample) return
        try { progress.flush(api, edition) } catch (_: ApiError) {}
    }

    fun clear() { content.clear(); progress.clear() }
}
