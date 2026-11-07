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

/** Per-line tutor data computed at build time by runtime/tutor/explain.py (ADR 0014). */
@Serializable
data class LineInfo(val n: Int, val explain: String, val constructs: List<String> = emptyList(), val shape: String = "")

/** Where the book teaches a construct. [sample] is true until the real book registry replaces the sample file. */
@Serializable
data class BookRef(val name: String, val unit: String, val section: String, val topic: String, val title: String,
                   val sample: Boolean = false, val page: Int? = null) {
    val label: String get() = "Book ${unit}, ${section} ${title}" + (page?.let { ", page $it" } ?: "") +
        (if (sample) " (sample reference)" else "")
}

/** A book program: code, optional stdin, author notes (X2), per-line tutor data, book references, and its recording. */
@Serializable
data class StepProgram(
    val id: String,
    val title: String,
    val source: String,
    val keywords: List<String> = emptyList(),
    val stdin: String = "",
    @kotlinx.serialization.SerialName("step_notes") val stepNotes: Map<String, String> = emptyMap(),
    val lines: List<LineInfo> = emptyList(),
    val refs: Map<String, BookRef> = emptyMap(),
    val recording: Recording,
) {
    fun lineInfo(n: Int): LineInfo? = lines.firstOrNull { it.n == n }
    fun refsFor(n: Int): List<BookRef> = lineInfo(n)?.constructs.orEmpty().mapNotNull { refs[it] }.distinctBy { it.topic }
}

object Recordings {
    const val SUPPORTED_VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    fun parsePrograms(text: String): List<StepProgram> =
        json.decodeFromString<List<StepProgram>>(text).filter { it.recording.version == SUPPORTED_VERSION }
}
