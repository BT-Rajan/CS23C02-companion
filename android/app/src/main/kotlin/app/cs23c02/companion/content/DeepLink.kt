package app.cs23c02.companion.content

/**
 * QR codes in the printed book (book decision X5) open `cs23c02://o/<ID>`: a private scheme that only this app handles,
 * so a browser has nothing to open. The link carries only a public content ID; the content itself still needs a signed-in
 * account with the book activated, so a link is useless without the app and an account.
 */
object DeepLink {
    const val SCHEME = "cs23c02"
    private val SHAPE = Regex("^cs23c02://o/([A-Z]{3}-[0-9]{4})/?$")

    /** The content ID, or null for anything that is not exactly a book link. */
    fun parse(uri: String?): String? = uri?.trim()?.let { SHAPE.matchEntire(it)?.groupValues?.get(1) }

    sealed interface Target {
        data class Lesson(val lessonId: String) : Target
        data class Practice(val lessonId: String, val index: Int) : Target
        data class Exercise(val exerciseId: String, val lessonId: String) : Target
        data class Program(val lessonId: String, val title: String, val code: String, val stdin: String) : Target
        data class Assessment(val assessmentId: String, val unitId: String) : Target
        data object NotFound : Target
    }

    /** Where an ID leads in the installed package: exactly that lesson, program, question, exercise or assessment. */
    fun resolve(pkg: InstalledPackage, id: String): Target {
        val nav = pkg.manifest.nav
        val lessonIds = nav.lessonIds()
        fun lessons() = lessonIds.asSequence().mapNotNull { l -> runCatching { pkg.lesson(l) }.getOrNull() }
        return when (id.take(3)) {
            "LSN" -> if (id in lessonIds) Target.Lesson(id) else Target.NotFound
            "TOP" -> nav.units.flatMap { it.topics }.firstOrNull { it.id == id }?.lessons?.firstOrNull()?.let { Target.Lesson(it) }
                ?: Target.NotFound
            "ITM" -> lessons().firstNotNullOfOrNull { l ->
                l.practice.items.indexOf(id).takeIf { it >= 0 }?.let { Target.Practice(l.id, it) } } ?: Target.NotFound
            "EXR" -> lessons().firstOrNull { id in it.practice.exercises }?.let { Target.Exercise(id, it.id) } ?: Target.NotFound
            "COD" -> lessons().firstNotNullOfOrNull { l ->
                l.blocks.firstOrNull { it.type == "code" && it.id == id && it.code != null }
                    ?.let { Target.Program(l.id, it.title ?: l.title, it.code!!, it.stdin ?: "") } } ?: Target.NotFound
            "ASM" -> nav.units.firstOrNull { it.assessment == id }?.let { Target.Assessment(id, it.id) } ?: Target.NotFound
            else -> Target.NotFound
        }
    }
}
