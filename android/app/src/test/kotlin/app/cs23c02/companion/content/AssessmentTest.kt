package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class AssessmentTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
    private val keys = SamplePracticeChecker.parse(File("src/debug/assets/sample-practice-keys.json").readText())
    private var now = Instant.parse("2026-11-06T10:00:00Z")
    private val pkg by lazy {
        val s = ContentStore(File(tmp.root, "pkg"), Sample.KEY, "0.0.1")
        runBlocking { s.install(Sample.manifest, Sample.signature) { Sample.file(it.path) } }
        s.current()!!
    }
    private fun engine() = SampleAssessmentEngine({ pkg }, keys.assessmentKeys) { now }
    private val right = mapOf("ITM-9101" to "c", "ITM-9102" to "b", "ITM-9103" to "b")

    @Test fun unitsLinkToTheirAssessmentsAndPublicFilesCarryNoAnswers() {
        assertEquals(listOf("ASM-9001", "ASM-9002"), pkg.manifest.nav.units.map { it.assessment })
        assertEquals(listOf("ITM-9101", "ITM-9102", "ITM-9103"), pkg.assessment("ASM-9001")!!.items)
        val raw = Sample.file("items/ITM-9101.json").decodeToString()
        assertFalse(raw.contains("answer") || raw.contains("SAMPLE:"))
    }

    @Test fun practiceCannotAnswerAnAssessmentQuestion() {
        try { keys.check("ITM-9101", "c"); fail() } catch (_: ContentError) {}
    }

    @Test fun sampleEngineFollowsTheServerRules() {
        val e = engine()
        val a = e.start("ASM-9001")
        assertEquals(3, a.questions.size); assertTrue(a.review.isEmpty()); assertNull(a.score)
        assertEquals(a.attemptId, e.start("ASM-9001").attemptId)                        // resume, not a second attempt
        e.save(a.attemptId, 2, right)
        e.save(a.attemptId, 1, mapOf("ITM-9101" to "a"))                                // a stale save is ignored
        val r = e.submit(a.attemptId, 3, right)
        assertEquals(3, r.score); assertEquals(3, r.maxScore)
        assertEquals(r.submittedAt, e.submit(a.attemptId, 9, emptyMap()).submittedAt)  // idempotent
        assertTrue(r.review.all { it.correct && it.explanation.startsWith("SAMPLE:") })
        try { e.start("ASM-9001"); fail() } catch (x: ContentError) { assertTrue(x.message!!.contains("24 hours")) }
        assertNotNull(e.history("ASM-9001").nextAttemptAt)
        now = now.plus(Duration.ofHours(25))
        assertNotEquals(a.attemptId, e.start("ASM-9001").attemptId)
    }

    private fun assertNotEquals(x: Any, y: Any) = assertTrue(x != y)

    @Test fun expiredSampleAttemptIsMarkedFromSavedAnswers() {
        val e = engine()
        val a = e.start("ASM-9001")
        e.save(a.attemptId, 1, mapOf("ITM-9101" to "c"))
        now = now.plus(Duration.ofHours(3)).plus(Duration.ofMinutes(10))
        val v = e.attempt(a.attemptId)
        assertTrue(v.submitted && v.autoSubmitted); assertEquals(1, v.score)
    }

    @Test fun assessmentFlowThroughTheViewModel() {
        val api = ApiClient("https://api.invalid", InMemoryTokenStore())
        val vm = AppViewModel(api, "test", previewTutor = true, readerFor = { _ ->
            ReaderRepository(api, ContentStore(File(tmp.root, "v"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "p.json")),
                samplePackage = { (Sample.signature + "\n").toByteArray() + Sample.manifest to m.files.associate { it.path to Sample.file(it.path) } },
                sampleChecker = keys) { p -> SampleAssessmentEngine(p, keys.assessmentKeys) }
        })
        vm.openSampleReader()
        awaitUntil { vm.reader.value.pkg != null }
        vm.openUnit("UNT-9001")
        vm.openAssessment("ASM-9001", "UNT-9001")
        awaitUntil { vm.assessment.value.history != null }
        assertTrue((vm.state.value.screen as Screen.AssessmentPage).back is Screen.UnitPage)
        vm.startAssessment("ASM-9001")
        awaitUntil { vm.assessment.value.attempt != null }
        val qs = vm.assessment.value.attempt!!.questions
        qs.forEach { q -> vm.chooseAnswer(q.itemId, right.getValue(q.itemId)) }
        assertEquals(3, vm.assessment.value.seq)
        vm.gotoQuestion(99); assertEquals(2, vm.assessment.value.index)
        vm.submitAssessment()
        awaitUntil { vm.assessment.value.attempt?.submitted == true }
        assertEquals(3, vm.assessment.value.attempt!!.score)
        vm.back()                                                   // from the result: back to the intro, history refreshed
        awaitUntil { vm.assessment.value.history?.attempts?.size == 1 && vm.assessment.value.attempt == null }
        assertNotNull(vm.assessment.value.history!!.nextAttemptAt)
        vm.openMe()
        awaitUntil { vm.me.value.summary != null }
        assertEquals(3, vm.me.value.summary!!.assessments.single().bestScore)
    }

    @Test fun serverRequestsCarryOnlyChoices() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = ApiClient(server.url("/").toString(), InMemoryTokenStore(Tokens("A", "R", Long.MAX_VALUE)), OkHttpClient(), Dispatchers.IO)
        val id = "0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d"
        server.enqueue(MockResponse().setBody("""{"attempt_id":"$id","seq":2,"saved":1}"""))
        server.enqueue(MockResponse().setBody("""{"attempt_id":"$id","assessment_id":"ASM-0002","status":"submitted","score":1,"max_score":3,"review":[]}"""))
        api.saveAnswers(id, 2, mapOf("ITM-0005" to "a"))
        val res = api.submitAssessment(id, "11111111-2222-4333-8444-555555555555", 3, mapOf("ITM-0005" to "a"))
        assertEquals(1, res.score)
        val save = server.takeRequest(); val sub = server.takeRequest()
        assertEquals("PUT", save.method); assertEquals("""{"seq":2,"answers":{"ITM-0005":"a"}}""", save.body.readUtf8())
        assertEquals("""{"request_id":"11111111-2222-4333-8444-555555555555","seq":3,"answers":{"ITM-0005":"a"}}""", sub.body.readUtf8())
        server.shutdown()
    }

    private fun awaitUntil(cond: () -> Boolean) {
        val t = System.currentTimeMillis() + 10_000
        while (!cond() && System.currentTimeMillis() < t) Thread.sleep(10)
        assertTrue("timed out", cond())
    }
}
