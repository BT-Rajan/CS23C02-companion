package app.cs23c02.companion.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

/** Book QR codes (X5): only the exact private-scheme shape is accepted, and each ID opens exactly its content. */
class DeepLinkTest {
    @Test fun onlyExactBookLinksAreAccepted() {
        assertEquals("COD-0004", DeepLink.parse("cs23c02://o/COD-0004"))
        assertEquals("TOP-0012", DeepLink.parse("cs23c02://o/TOP-0012/"))
        for (bad in listOf(null, "", "https://example.org/o/COD-0004", "cs23c02://o/cod-0004", "cs23c02://o/COD-4",
                "cs23c02://x/COD-0004", "cs23c02://o/COD-0004?next=https://evil", "cs23c02://o/../../COD-0004",
                "cs23c02://o/COD-0004/extra", "CS23C02://o/COD-0004")) {
            assertNull(bad, DeepLink.parse(bad))
        }
    }

    private val lessons = mapOf(
        "LSN-0001" to """{"id":"LSN-0001","title":"Your first program","topic":"TOP-0007","blocks":[
            {"type":"paragraph","text":"A program is a sequence of statements."},
            {"type":"code","id":"COD-0002","title":"Print a course card","code":"print(\"hi\")"}],
            "practice":{"items":["ITM-0019","ITM-0020","ITM-0021"],"exercises":[]}}""",
        "LSN-0002" to """{"id":"LSN-0002","title":"Sum with while","topic":"TOP-0017","blocks":[
            {"type":"code","id":"COD-0021","title":"Sum from 1 to n","code":"n = int(input())","stdin":"5\n"}],
            "practice":{"items":["ITM-0049"],"exercises":["EXR-0006"]}}""")

    private fun pkg(): InstalledPackage {
        val data = lessons.map { (id, json) -> "lessons/$id.json" to json.toByteArray() }.toMap()
        val files = data.map { (path, bytes) ->
            FileEntry(path, MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())
        }
        val nav = Nav(listOf(NavUnit("UNT-0001", "Structured Programming", listOf(
            NavTopic("TOP-0007", "Your first program", listOf("LSN-0001")),
            NavTopic("TOP-0017", "Iteration with while", listOf("LSN-0002"))), assessment = "ASM-0002")))
        val manifest = Manifest("cs23c02-package/0", BookInfo("BOK-0001", "Book", "1", "0.1.0", "0.1.0", "3.12"), nav = nav, files = files)
        return InstalledPackage(manifest) { e -> data.getValue(e.path) }
    }

    @Test fun eachKindOfIdOpensExactlyItsContent() {
        val p = pkg()
        assertEquals(DeepLink.Target.Lesson("LSN-0002"), DeepLink.resolve(p, "TOP-0017"))
        assertEquals(DeepLink.Target.Lesson("LSN-0001"), DeepLink.resolve(p, "LSN-0001"))
        assertEquals(DeepLink.Target.Practice("LSN-0001", 2), DeepLink.resolve(p, "ITM-0021"))
        assertEquals(DeepLink.Target.Exercise("EXR-0006", "LSN-0002"), DeepLink.resolve(p, "EXR-0006"))
        assertEquals(DeepLink.Target.Program("LSN-0002", "Sum from 1 to n", "n = int(input())", "5\n"), DeepLink.resolve(p, "COD-0021"))
        assertEquals(DeepLink.Target.Assessment("ASM-0002", "UNT-0001"), DeepLink.resolve(p, "ASM-0002"))
    }

    @Test fun unknownIdsGoNowhere() {
        val p = pkg()
        for (id in listOf("TOP-0099", "COD-0999", "ITM-0500", "EXR-0042", "ASM-0009", "LSN-0077", "FIG-0001")) {
            assertEquals(id, DeepLink.Target.NotFound, DeepLink.resolve(p, id))
        }
    }
}
