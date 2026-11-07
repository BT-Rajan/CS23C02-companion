package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.Screen
import app.cs23c02.companion.ui.screens.stemText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PracticeTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
    private val keys = SamplePracticeChecker.parse(File("src/debug/assets/sample-practice-keys.json").readText())

    private fun sampleVm(): AppViewModel {
        val api = ApiClient("https://api.invalid", InMemoryTokenStore())
        return AppViewModel(api, "test", previewTutor = true, readerFor = { _ ->
            ReaderRepository(api, ContentStore(File(tmp.root, "pkg"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "p.json")),
                { (Sample.signature + "\n").toByteArray() + Sample.manifest to m.files.associate { it.path to Sample.file(it.path) } }, keys)
        })
    }
    private fun AppViewModel.awaitPkg() { val t = System.currentTimeMillis() + 10_000; while (reader.value.pkg == null && System.currentTimeMillis() < t) Thread.sleep(10) }
    private fun AppViewModel.awaitVerdict(id: String) { val t = System.currentTimeMillis() + 10_000; while (practice.value.verdicts[id] == null && practice.value.error == null && System.currentTimeMillis() < t) Thread.sleep(10) }

    @Test fun publicItemsCarryNoAnswers() {
        val pkg = ContentStore(File(tmp.root, "x"), Sample.KEY, "0.0.1")
        kotlinx.coroutines.runBlocking { pkg.install(Sample.manifest, Sample.signature) { Sample.file(it.path) } }
        val it = pkg.current()!!.item("ITM-9003")!!
        assertEquals("trace", it.type); assertTrue(it.code!!.contains("score + 5"))
        val raw = Sample.file("items/ITM-9003.json").decodeToString()
        assertFalse(raw.contains("answer") || raw.contains("explanation") || raw.contains("SAMPLE:"))
    }

    @Test fun sampleCheckerFollowsTheServerPolicy() {
        val k = SamplePracticeChecker.parse(File("src/debug/assets/sample-practice-keys.json").readText())
        val w1 = k.check("ITM-9001", "a")
        assertFalse(w1.correct); assertFalse(w1.revealed); assertNull(w1.answer); assertEquals("Comparing uses ==, not =.", w1.why)
        val w2 = k.check("ITM-9001", "c")
        assertTrue(w2.revealed); assertEquals("b", w2.answer); assertTrue(w2.explanation!!.startsWith("SAMPLE:"))
        val r = k.check("ITM-9002", "b")
        assertTrue(r.correct && r.revealed && r.why == null)
    }

    @Test fun practiseALessonEndToEnd() {
        val vm = sampleVm()
        vm.openSampleReader(); vm.awaitPkg()
        vm.openLesson("LSN-9001")
        vm.openPractice("LSN-9001")
        var p = vm.state.value.screen as Screen.PracticePage
        assertEquals(0, p.index); assertTrue(p.back is Screen.LessonPage)

        vm.checkPractice("ITM-9001", "a"); vm.awaitVerdict("ITM-9001")
        assertFalse(vm.practice.value.verdicts["ITM-9001"]!!.revealed)
        vm.retryPractice("ITM-9001")
        assertNull(vm.practice.value.verdicts["ITM-9001"])
        vm.checkPractice("ITM-9001", "b"); vm.awaitVerdict("ITM-9001")
        assertTrue(vm.practice.value.verdicts["ITM-9001"]!!.correct)
        vm.retryPractice("ITM-9001")                                    // nothing to retry after a revealed verdict
        assertTrue(vm.practice.value.verdicts["ITM-9001"] != null)

        vm.openPractice("LSN-9001", 1)
        p = vm.state.value.screen as Screen.PracticePage
        assertEquals(1, p.index); assertTrue(p.back is Screen.LessonPage)  // Next keeps Back pointing at the lesson
        vm.back()
        assertEquals("LSN-9001", (vm.state.value.screen as Screen.LessonPage).lessonId)
        assertTrue(vm.reader.value.completed.isEmpty())                     // practice never counts as progress
    }

    @Test fun serverVerdictAndOfflineMessage() {
        val server = MockWebServer().apply { start() }
        val api = ApiClient(server.url("/").toString(), InMemoryTokenStore(Tokens("A", "R", Long.MAX_VALUE)), OkHttpClient(), Dispatchers.IO)
        val repo = ReaderRepository(api, ContentStore(File(tmp.root, "s"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "sp.json")))
        server.enqueue(MockResponse().setBody("""{"item_id":"ITM-0001","correct":false,"attempts_wrong":1,"why":"That would be ==.","revealed":false}"""))
        val v = kotlinx.coroutines.runBlocking { repo.checkPractice("ITM-0001", "a", "ed1") }
        assertEquals("That would be ==.", v.why)
        val req = server.takeRequest()
        assertEquals("/v1/practice/items/ITM-0001/answer", req.path)
        assertEquals("""{"option":"a","edition":"ed1"}""", req.body.readUtf8())   // only the choice; the server decides
        server.shutdown()
        try { kotlinx.coroutines.runBlocking { repo.checkPractice("ITM-0001", "a", "ed1") } ; throw AssertionError() }
        catch (e: app.cs23c02.companion.api.ApiError) { assertTrue(e.isNetwork) }
    }

    @Test fun backticksInStemsBecomeCode() {
        val t = stemText("What does `x = 5` do?")
        assertEquals("What does x = 5 do?", t.text)
        assertEquals(1, t.spanStyles.size)
    }
}
