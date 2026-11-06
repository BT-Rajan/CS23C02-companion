package app.cs23c02.companion.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** App Content Package v0 (docs/content-interface.md §2), as built by content-tools/cs23c02_content/build.py. */
@Serializable
data class Manifest(
    val format: String,
    val book: BookInfo,
    @SerialName("signed_at") val signedAt: String = "",
    val nav: Nav,
    val files: List<FileEntry>,
)

@Serializable
data class BookInfo(val id: String, val title: String, val edition: String, val version: String,
                    @SerialName("min_app_version") val minAppVersion: String, val python: String)

@Serializable data class Nav(val units: List<NavUnit>)
@Serializable data class NavUnit(val id: String, val title: String, val topics: List<NavTopic>)
@Serializable data class NavTopic(val id: String, val title: String, val lessons: List<String>)
@Serializable data class FileEntry(val path: String, val sha256: String, val size: Long)

/** One app lesson (book decision R1: one LSN delivers one topic or contiguous subtopics). */
@Serializable
data class Lesson(val id: String, val title: String, val topic: String, val minutes: Int = 0, val blocks: List<Block> = emptyList())

/**
 * A semantic block. Known types: paragraph, heading, definition, code, syntax, note, list. Unknown types from a newer
 * package are skipped by the reader rather than crashing it.
 */
@Serializable
data class Block(
    val type: String,
    val text: String? = null,
    val term: String? = null,
    val code: String? = null,
    @SerialName("expected_output") val expectedOutput: String? = null,
    val items: List<String>? = null,
)

internal val ContentJson = Json { ignoreUnknownKeys = true }

/** Where a lesson sits in the book, for titles like "Unit 1 · Values and variables". */
data class LessonPlace(val unitIndex: Int, val unit: NavUnit, val topic: NavTopic, val lessonId: String)

fun Nav.lessonIds(): List<String> = units.flatMap { u -> u.topics.flatMap { it.lessons } }

fun Nav.place(lessonId: String): LessonPlace? {
    units.forEachIndexed { i, u -> u.topics.forEach { t -> if (lessonId in t.lessons) return LessonPlace(i, u, t, lessonId) } }
    return null
}

fun Nav.next(lessonId: String): String? = lessonIds().let { ids -> ids.getOrNull(ids.indexOf(lessonId) + 1) }
