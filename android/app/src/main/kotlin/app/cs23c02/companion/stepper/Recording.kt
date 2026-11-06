package app.cs23c02.companion.stepper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors runtime/README.md "Recording format (version 1)". Produced by runtime/stepper/stepper.py. */
@Serializable
data class Recording(
    val version: Int,
    val status: String,
    val steps: List<Step>,
    val stdout: String = "",
    val error: RunError? = null,
)

@Serializable
data class Step(
    val event: String,
    val line: Int,
    val stack: List<Frame>,
    val changed: List<String> = emptyList(),
    val out: Int = 0,
    val value: Value? = null,
    val exc: Exc? = null,
)

@Serializable
data class Frame(val id: Int, val name: String, val line: Int, val vars: Map<String, Value>)

@Serializable
data class Value(val r: String, val t: String)

@Serializable
data class Exc(val type: String, val message: String)

@Serializable
data class RunError(val type: String, val message: String, val line: Int? = null, val column: Int? = null)

/** A worked example ready to step: code, optional stdin, author notes per line (book decision X2), and its recording. */
@Serializable
data class StepProgram(
    val id: String,
    val title: String,
    val source: String,
    val stdin: String = "",
    @kotlinx.serialization.SerialName("step_notes") val stepNotes: Map<String, String> = emptyMap(),
    val recording: Recording,
)

object Recordings {
    const val SUPPORTED_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePrograms(text: String): List<StepProgram> =
        json.decodeFromString<List<StepProgram>>(text).filter { it.recording.version == SUPPORTED_VERSION }
}
