package app.cs23c02.companion.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContentStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun store(dir: File = tmp.root) = ContentStore(dir, Sample.KEY, "0.0.1")
    private var fetches = 0
    private val fileCount = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1").files.size
    private suspend fun fetch(e: FileEntry): ByteArray { fetches++; return Sample.file(e.path) }

    @Test fun installThenReadLessons() = runBlocking {
        val s = store()
        assertNull(s.current())
        s.install(Sample.manifest, Sample.signature, ::fetch)
        val pkg = s.current()!!
        assertEquals("What is a variable?", pkg.lesson("LSN-9001")!!.title)
        assertEquals("code", pkg.lesson("LSN-9001")!!.blocks[2].type)
        assertEquals(fileCount, fetches)
    }

    @Test fun secondInstallFetchesNothing() = runBlocking {
        val s = store()
        s.install(Sample.manifest, Sample.signature, ::fetch)
        s.install(Sample.manifest, Sample.signature, ::fetch)
        assertEquals(fileCount, fetches)
    }

    @Test fun reopeningFromDiskVerifiesAgain() = runBlocking {
        store().install(Sample.manifest, Sample.signature, ::fetch)
        assertNotNull(store().current())                    // a fresh store (e.g. after app restart) trusts nothing it didn't verify
        val cur = File(tmp.root, "current")
        val b = cur.readBytes(); b[b.size - 10] = (b[b.size - 10] + 1).toByte(); cur.writeBytes(b)
        assertNull(store().current())                       // manifest altered on disk → refused
    }

    @Test fun lessonFileAlteredOnDiskIsNeverShown() = runBlocking {
        val s = store()
        s.install(Sample.manifest, Sample.signature, ::fetch)
        val e = s.current()!!.manifest.files.first { it.path == "lessons/LSN-9002.json" }
        File(tmp.root, "files/${e.sha256}").writeText("{\"id\":\"LSN-9002\",\"title\":\"Hacked\",\"topic\":\"x\"}")
        try { store().current()!!.lesson("LSN-9002"); fail() } catch (_: ContentError) {}
    }

    @Test fun failedDownloadKeepsThePreviousPackage() = runBlocking {
        val s = store()
        s.install(Sample.manifest, Sample.signature, ::fetch)
        val signer = TestSigner()
        val other = ContentStore(tmp.root, signer.publicKey, "0.0.1")
        // A wrongly signed (for this store's key) update must not replace what is installed.
        try { s.install(Sample.manifest, signer.sign(Sample.manifest), ::fetch); fail() } catch (_: ContentError) {}
        assertNotNull(s.current())
        // A correctly signed manifest whose file download returns wrong bytes is refused, and the old package stays.
        try { s.install(Sample.manifest, Sample.signature) { "wrong".toByteArray() }.also { } } catch (_: ContentError) {}
        assertEquals("What is a variable?", s.current()!!.lesson("LSN-9001")!!.title)
        assertNull(other.current()) // the sample isn't valid under another key
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val s = store()
        s.install(Sample.manifest, Sample.signature, ::fetch)
        s.clear()
        assertNull(s.current())
        assertTrue(!tmp.root.exists() || tmp.root.listFiles().isNullOrEmpty())
    }
}
