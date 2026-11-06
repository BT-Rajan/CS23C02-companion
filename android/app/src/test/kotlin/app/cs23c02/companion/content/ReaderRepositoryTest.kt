package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Serves the signed sample package the way backend/app/content/router.py does (manifest + header; files by hash). */
class ReaderRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()
    private val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
    private val byHash = m.files.associate { it.sha256 to Sample.file(it.path) }
    var fileHits = 0
    var badSignature = false

    @Before fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path!!
                return when {
                    p.startsWith("/v1/content/manifest") -> MockResponse().setBody(Buffer().write(Sample.manifest))
                        .setHeader("X-Manifest-Signature", if (badSignature) TestSigner().sign(Sample.manifest) else Sample.signature)
                        .setHeader("X-Content-Version", m.book.version)
                    p.startsWith("/v1/content/files/") -> { fileHits++; MockResponse().setBody(Buffer().write(byHash[p.substringAfterLast('/')]!!)) }
                    p.startsWith("/v1/progress/events") -> MockResponse().setBody("""{"results":[]}""")
                    p.startsWith("/v1/progress") -> MockResponse().setBody("""{"edition_id":"ed","completed":["LSN-9001"],"resume":{"lesson_id":"LSN-9002","reason":"next"}}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }
    @After fun tearDown() = server.shutdown()

    private fun repo(): ReaderRepository {
        val api = ApiClient(server.url("/").toString(), InMemoryTokenStore(Tokens("A", "R", Long.MAX_VALUE)), OkHttpClient(), Dispatchers.IO)
        return ReaderRepository(api, ContentStore(File(tmp.root, "c"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "p.json")))
    }

    @Test fun firstSyncDownloadsVerifiesAndMergesProgress() = runBlocking {
        val r = repo()
        assertEquals(ReaderRepository.SyncOutcome.Updated, r.sync("ed"))
        assertEquals(5, fileHits)
        assertEquals("Changing a variable", r.content.current()!!.lesson("LSN-9002")!!.title)
        assertEquals(setOf("LSN-9001"), r.progress.state.completed)
        assertEquals(ReaderRepository.SyncOutcome.UpToDate, repo().sync("ed"))  // same version: no downloads
        assertEquals(5, fileHits)
    }

    @Test fun offlineKeepsWhatIsInstalled() = runBlocking {
        val r = repo()
        r.sync("ed")
        server.shutdown()
        assertEquals(ReaderRepository.SyncOutcome.Offline(hasContent = true), r.sync("ed"))
        assertEquals("What is a variable?", r.content.current()!!.lesson("LSN-9001")!!.title)
    }

    @Test fun wronglySignedManifestFromTheServerIsRefused() = runBlocking {
        badSignature = true
        val out = repo().sync("ed")
        assertTrue(out is ReaderRepository.SyncOutcome.Failed)
        assertEquals(0, fileHits) // nothing downloaded on the strength of an unverified manifest
    }

    @Test fun samplePackageLoadsFromTheApkWithoutTheServer() = runBlocking {
        val api = ApiClient("https://api.invalid", InMemoryTokenStore())
        val r = ReaderRepository(api, ContentStore(File(tmp.root, "s"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "sp.json"))) {
            (Sample.signature + "\n").toByteArray() + Sample.manifest to m.files.associate { it.path to Sample.file(it.path) }
        }
        assertEquals(ReaderRepository.SyncOutcome.Updated, r.sync(null))
        assertEquals(5, r.content.current()!!.manifest.nav.lessonIds().size)
        assertEquals(0, server.requestCount)
    }
}
