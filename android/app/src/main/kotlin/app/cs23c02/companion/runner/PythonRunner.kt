package app.cs23c02.companion.runner

import app.cs23c02.companion.stepper.BookRef
import app.cs23c02.companion.stepper.LineInfo
import app.cs23c02.companion.stepper.Recording
import app.cs23c02.companion.stepper.StepProgram
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface RunnerState {
    data object Starting : RunnerState
    /** [loadMs]: time from start to ready (load + warm-up); [mode]: "worker" or "page" fallback. */
    data class Ready(val loadMs: Long, val mode: String) : RunnerState
    data class Failed(val reason: String) : RunnerState
}

/** One answer from the on-device Python (runtime/tutor/live.py): the recording plus line explanations and book refs. */
@Serializable
data class LiveResult(val recording: Recording, val lines: List<LineInfo> = emptyList(), val refs: Map<String, BookRef> = emptyMap()) {
    var elapsedMs: Long = 0

    fun toProgram(title: String, source: String, stdin: String, id: String = "own-code") =
        StepProgram(id = id, title = title, source = source, stdin = stdin, lines = lines, refs = refs, recording = recording)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): LiveResult = json.decodeFromString(serializer(), text)
    }
}

/** Runs the student's code on the phone (ADR 0013 slice 3). Practice only: results never count for progress. */
interface PythonRunner {
    val state: StateFlow<RunnerState>
    /** Start loading in the background (ADR 0006 warm-up trigger); safe to call repeatedly. */
    fun warmUp()
    /** [echo]: show typed input in the output like a terminal (stepper); false = exactly what the program printed. */
    suspend fun run(source: String, stdin: String = "", echo: Boolean = true): LiveResult
    /** Free the runtime (about 100 MB on a phone) when no screen needs it. */
    fun close() {}
}
