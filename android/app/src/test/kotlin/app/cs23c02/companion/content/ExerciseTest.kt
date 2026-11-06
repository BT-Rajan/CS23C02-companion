package app.cs23c02.companion.content

import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.auth.Tokens
import app.cs23c02.companion.runner.CPythonRunner
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private val m = PackageVerifier.verifyManifest(Sample.manifest, Sample.signature, Sample.KEY, "0.0.1")
    private val keys = SamplePracticeChecker.parse(File("src/debug/assets/sample-practice-keys.json").readText())
    private val py = CPythonRunner()
    private fun grader() = SampleExerciseGrader(keys.exercises) { code, stdin ->
        val r = py.run(code, stdin, echo = false)
        val st = when (r.recording.status) { "ok" -> "ok"; "step_limit", "time_limit", "killed" -> "timeout"; else -> "error" }
        SampleExerciseGrader.RunOutput(st, r.recording.stdout, r.recording.error?.let { "${it.type}: ${it.message}" })
    }
    private fun pkg(): InstalledPackage {
        val s = ContentStore(File(tmp.root, "pkg"), Sample.KEY, "0.0.1")
        runBlocking { s.install(Sample.manifest, Sample.signature) { Sample.file(it.path) } }
        return s.current()!!
    }

    @Test fun publicExerciseHasOnlyPublicTests() {
        val ex = pkg().exercise("EXR-9001")!!
        assertEquals(listOf(PublicTest("4\n", "8\n")), ex.tests)
        val raw = Sample.file("exercises/EXR-9001.json").decodeToString()
        assertFalse(raw.contains("-3") || raw.contains("hint") || raw.contains("reference") || raw.contains("SAMPLE:"))
    }

    @Test fun sampleGraderMatchesTheServerPolicy() = runBlocking {
        val ex = pkg().exercise("EXR-9001")!!
        val g = grader()
        val wrong = g.submit(ex, "n = int(input())\nprint(abs(n) * 2)\n")       // passes the example and 0, fails -3
        assertFalse(wrong.passed)
        assertTrue(wrong.public.single().passed)
        assertEquals(1, wrong.hidden.passed); assertEquals(2, wrong.hidden.total); assertEquals("wrong output", wrong.hidden.firstFailure)
        assertEquals(1, wrong.help.hintsUnlocked); assertEquals(2, wrong.help.nextHintIn); assertEquals(4, wrong.help.solutionIn)
        assertEquals(1, g.help(ex).hints.size); assertNull(g.help(ex).solution)
        val err = g.submit(ex, "print(1/0)")
        assertEquals("error", err.public.single().status)
        val right = g.submit(ex, "n = int(input())\nprint(n * 2)\n")
        assertTrue(right.passed && right.help.solutionUnlocked)
        assertTrue(g.help(ex).solution!!.code.contains("n * 2"))
    }

    @Test fun outputComparisonMatchesTheSandbox() {
        assertTrue(SampleExerciseGrader.sameOutput("8\n", "8"))
        assertTrue(SampleExerciseGrader.sameOutput("a  \nb\n\n", "a\nb"))
        assertFalse(SampleExerciseGrader.sameOutput("8 \n9", "8\n 9"))
    }

    @Test fun exerciseFlowThroughTheViewModel() {
        val api = ApiClient("https://api.invalid", InMemoryTokenStore())
        val vm = AppViewModel(api, "test", previewTutor = true, runner = py, readerFor = { _ ->
            ReaderRepository(api, ContentStore(File(tmp.root, "vpkg"), Sample.KEY, "0.0.1"), ProgressStore(File(tmp.root, "p.json")),
                samplePackage = { (Sample.signature + "\n").toByteArray() + Sample.manifest to m.files.associate { it.path to Sample.file(it.path) } },
                sampleChecker = keys, sampleExercises = grader())
        })
        vm.openSampleReader()
        val t = System.currentTimeMillis() + 10_000
        while (vm.reader.value.pkg == null && System.currentTimeMillis() < t) Thread.sleep(10)
        vm.openLesson("LSN-9002")
        vm.openExercise("EXR-9001", "LSN-9002")
        val page = vm.state.value.screen as Screen.ExercisePage
        assertTrue(page.back is Screen.LessonPage)
        val ex = vm.reader.value.pkg!!.exercise("EXR-9001")!!

        vm.runExercise(ex, "n = int(input())\nprint(n * 2)\n")
        awaitUntil { vm.exercise.value.runs["EXR-9001"] != null }
        assertEquals("8\n", vm.exercise.value.runs["EXR-9001"]!!.recording.stdout)   // Run uses the first example's input

        vm.checkExercise(ex, "n = int(input())\nprint(n + 4)\n")                     // right for 4, wrong otherwise
        awaitUntil { vm.exercise.value.results["EXR-9001"] != null && !vm.exercise.value.checking }
        assertFalse(vm.exercise.value.results["EXR-9001"]!!.passed)
        awaitUntil { vm.exercise.value.help["EXR-9001"] != null }                     // hint 1 fetched automatically
        assertEquals(1, vm.exercise.value.help["EXR-9001"]!!.hints.size)
        assertEquals("n = int(input())\nprint(n + 4)\n", vm.draftFor("EXR-9001"))     // the attempt is saved as the draft
        assertTrue(vm.reader.value.completed.isEmpty())
    }

    @Test fun serverPathSendsOnlyTheCode() = runBlocking {
        val server = MockWebServer().apply { start() }
        val api = ApiClient(server.url("/").toString(), InMemoryTokenStore(Tokens("A", "R", Long.MAX_VALUE)), OkHttpClient(), Dispatchers.IO)
        server.enqueue(MockResponse().setBody("""{"exercise_id":"EXR-0001","passed":false,"public":[{"stdin":"4\n","expect":"8\n","got":"6\n","passed":false,"status":"ok","error":null}],
            "hidden":{"passed":0,"total":2,"first_failure":"wrong output"},"help":{"failed_checks":1,"passed":false,"hints_unlocked":1,"hints_total":2,"next_hint_in":2,"solution_unlocked":false,"solution_in":4}}"""))
        val r = api.submitExercise("EXR-0001", "print(6)", "ed1")
        assertEquals("6\n", r.public.single().got); assertEquals(1, r.help.hintsUnlocked)
        val req = server.takeRequest()
        assertEquals("/v1/exercises/EXR-0001/submissions", req.path)
        assertEquals("""{"code":"print(6)","edition":"ed1"}""", req.body.readUtf8())
        server.shutdown()
    }

    private fun awaitUntil(cond: () -> Boolean) {
        val t = System.currentTimeMillis() + 20_000
        while (!cond() && System.currentTimeMillis() < t) Thread.sleep(20)
        assertTrue("timed out", cond())
    }
}
