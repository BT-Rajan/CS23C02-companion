package app.cs23c02.companion

import android.content.Context
import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.content.ContentError
import app.cs23c02.companion.content.ContentStore
import app.cs23c02.companion.content.Manifest
import app.cs23c02.companion.content.ProgressStore
import app.cs23c02.companion.content.ReaderRepository
import app.cs23c02.companion.content.SampleExerciseGrader
import app.cs23c02.companion.content.SamplePracticeChecker
import app.cs23c02.companion.runner.PythonRunner
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Builds the lessons + progress repository. Takes the APPLICATION context only (never an Activity), because the result lives
 * in the ViewModel. Lessons live in no-backup app storage; the account's copy is wiped on sign-out (attack C5).
 * [sample] = the debug-only SAMPLE package, answers and exercise grading bundled in the APK; never used for book content.
 */
fun buildReader(ctx: Context, api: ApiClient, sample: Boolean, runner: PythonRunner?): ReaderRepository {
    val base = File(ctx.noBackupFilesDir, if (sample) "sample" else "content")
    val key = if (sample) SampleContent.PUBLIC_KEY else BuildConfig.CONTENT_SIGNING_PUBKEY
    val store = ContentStore(File(base, "package"), key, BuildConfig.VERSION_NAME)
    val progress = ProgressStore(File(base, "progress.json"))
    if (!sample) return ReaderRepository(api, store, progress)

    val assets = ctx.assets
    val dir = SampleContent.ASSET_DIR
    val loader: suspend () -> Pair<ByteArray, Map<String, ByteArray>> = {
        val manifest = assets.open("$dir/manifest.json").use { it.readBytes() }
        val sig = assets.open("$dir/manifest.sig").use { it.readBytes() }.decodeToString().trim()
        // Paths come from the (not yet verified) manifest only to know what to read; install() verifies everything.
        val paths = Json { ignoreUnknownKeys = true }.decodeFromString(Manifest.serializer(), manifest.decodeToString()).files.map { it.path }
        (sig + "\n").toByteArray() + manifest to paths.associateWith { p -> assets.open("$dir/$p").use { it.readBytes() } }
    }
    val checker = SamplePracticeChecker.parse(assets.open(SampleContent.PRACTICE_KEYS).use { it.readBytes() }.decodeToString())
    val grader = SampleExerciseGrader(checker.exercises) { code, stdin ->
        val r = runner?.run(code, stdin, echo = false) ?: throw ContentError("Python is not ready yet.")
        val status = when (r.recording.status) {
            "ok" -> "ok"; "step_limit", "time_limit", "killed" -> "timeout"; "output_limit" -> "output_limit"; else -> "error"
        }
        SampleExerciseGrader.RunOutput(status, r.recording.stdout, r.recording.error?.let { "${it.type}: ${it.message}" })
    }
    return ReaderRepository(api, store, progress, loader, checker, grader)
}
