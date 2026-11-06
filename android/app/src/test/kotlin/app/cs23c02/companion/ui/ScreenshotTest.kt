package app.cs23c02.companion.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.api.Entitlement
import app.cs23c02.companion.api.Me
import app.cs23c02.companion.auth.InMemoryTokenStore
import app.cs23c02.companion.ui.screens.ActivateScreen
import app.cs23c02.companion.ui.screens.CreateAccountScreen
import app.cs23c02.companion.ui.screens.HomeScreen
import app.cs23c02.companion.ui.ReaderUi
import app.cs23c02.companion.ui.screens.ResetScreen
import app.cs23c02.companion.ui.screens.SignInScreen
import app.cs23c02.companion.ui.screens.VerifyEmailScreen
import app.cs23c02.companion.ui.screens.WelcomeScreen
import app.cs23c02.companion.ui.theme.CompanionTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every A1 screen (light, dark, and 200% font) to PNGs for review: gradle :app:testDebugUnitTest -Pscreenshots.
 * 360x640 dp is the design system's small-phone target. These are pictures for a human, not pixel assertions.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotTest {
    private val out = System.getProperty("screens.out") ?: "build/screens"
    private val vm by lazy { AppViewModel(ApiClient("https://api.invalid", InMemoryTokenStore()), "test") }
    private val me = Me("student@example.com", listOf(Entitlement("ed1", "CS23C02 First Edition", "2026-11-06T00:00:00Z")))

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) = captureRoboImage("$out/$name.png") {
        CompanionTheme(dark = dark) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) { content() }
        }
    }

    @Test fun welcome() { shot("01-welcome") { WelcomeScreen(vm) }; shot("01-welcome-dark", dark = true) { WelcomeScreen(vm) } }

    @Test fun createAccountWithErrors() = shot("02-create-account-errors") {
        CreateAccountScreen(vm, UiState(Screen.CreateAccount, fieldErrors = mapOf(
            "email" to "That doesn't look like an email address.", "password" to "Use a password of 10 to 128 characters.")))
    }

    @Test fun verify() = shot("03-verify") {
        VerifyEmailScreen(vm, UiState(Screen.VerifyEmail("student@example.com"), info = "We sent a 6-digit code to student@example.com."), "student@example.com")
    }

    @Test fun signInError() {
        val s = UiState(Screen.SignIn, error = "Email or password is incorrect.")
        shot("04-sign-in-error") { SignInScreen(vm, s) }
        shot("04-sign-in-error-dark", dark = true) { SignInScreen(vm, s) }
    }

    @Test fun reset() = shot("05-reset") { ResetScreen(vm, UiState(Screen.Reset("student@example.com")), "student@example.com") }

    @Test fun activate() {
        shot("06-activate") { ActivateScreen(vm, UiState(Screen.Activate)) }
        shot("06-activate-dark", dark = true) { ActivateScreen(vm, UiState(Screen.Activate, error = "That code can't be used. Check it and try again, or contact support.")) }
    }

    private val samplePkg by lazy {
        val dir = java.nio.file.Files.createTempDirectory("pkg").toFile()
        val store = app.cs23c02.companion.content.ContentStore(dir, app.cs23c02.companion.content.Sample.KEY, "0.0.1")
        kotlinx.coroutines.runBlocking { store.install(app.cs23c02.companion.content.Sample.manifest, app.cs23c02.companion.content.Sample.signature) { e ->
            app.cs23c02.companion.content.Sample.file(e.path) } }
    }
    private fun readerUi(done: Set<String> = setOf("LSN-9001"), open: String? = "LSN-9002") =
        ReaderUi(pkg = samplePkg, completed = done, lastOpened = open)

    @Test fun home() {
        shot("07-home") { HomeScreen(me, false, readerUi(), null, {}, {}, {}, {}, {}) }
        shot("07-home-dark", dark = true) { HomeScreen(me, false, readerUi(), null, {}, {}, {}, {}, {}) }
    }

    @Test fun homeFirstTimeAndSample() = shot("07-home-sample-start") {
        HomeScreen(Me("Sample content (test build)", emptyList()), true, readerUi(emptySet(), null), null, {}, {}, {}, {}, {})
    }

    @Test fun homeOfflineWithoutLessons() = shot("07-home-offline") {
        HomeScreen(me, false, ReaderUi(offline = true, problem = "You're offline. Connect once to download your lessons."), null, {}, {}, {}, null, {})
    }

    @Test fun unitPage() = shot("21-unit") {
        app.cs23c02.companion.ui.screens.UnitScreen(samplePkg, "UNT-9001", readerUi(), {}, {})
    }

    @Test fun lessonPage() {
        shot("22-lesson-top") { app.cs23c02.companion.ui.screens.LessonScreen(samplePkg, "LSN-9001", false, 0, { _, _ -> }, {}, {}, {}, {}, false) }
        shot("22-lesson-code") { app.cs23c02.companion.ui.screens.LessonScreen(samplePkg, "LSN-9003", false, 2, { _, _ -> }, {}, {}, {}, {}, false) }
        shot("22-lesson-end-dark", dark = true) { app.cs23c02.companion.ui.screens.LessonScreen(samplePkg, "LSN-9002", true, 4, { _, _ -> }, {}, {}, {}, {}, false) }
    }

    private val demos by lazy {
        app.cs23c02.companion.stepper.Recordings.parsePrograms(java.io.File("src/main/assets/stepper/demos.json").readText())
    }

    @Composable
    private fun StepAt(id: String, position: Int) {
        // Drive the screen to a position by replaying taps is slow in Robolectric; render the state directly instead.
        app.cs23c02.companion.ui.screens.StepperScreenAt(demos.single { it.id == id }, position)
    }

    @Test fun stepperLoop() {
        shot("09-stepper-loop") { StepAt("running-total", 4) }
        shot("09-stepper-loop-dark", dark = true) { StepAt("running-total", 9) }
    }

    @Test fun stepperFunctionCall() = shot("10-stepper-call") { StepAt("function-call", 5) }

    @Test fun stepperRecursion() = shot("11-stepper-recursion") { StepAt("recursion", 12) }


    @Test fun tutorSearch() = shot("13-tutor-search") {
        app.cs23c02.companion.ui.screens.TutorScreen(demos.filter { it.id == "recursion" || it.id == "even-or-odd" }, {}, {}, {})
    }

    private val total get() = demos.single { it.id == "running-total" }

    @Test fun typeItStart() = shot("14-typeit-next") {
        app.cs23c02.companion.ui.screens.TypeItScreen(total, {}, {}, initialText = "marks = [72, 85, 64]\ntotal = 0\n")
    }

    @Test fun typeItIndentProblem() {
        val t = "marks = [72, 85, 64]\ntotal = 0\nfor m in marks:\ntotal = total + m\n"
        shot("15-typeit-fix") { app.cs23c02.companion.ui.screens.TypeItScreen(total, {}, {}, initialText = t) }
        shot("15-typeit-fix-dark", dark = true) { app.cs23c02.companion.ui.screens.TypeItScreen(total, {}, {}, initialText = t) }
    }

    @Test fun typeItDone() = shot("16-typeit-done") {
        app.cs23c02.companion.ui.screens.TypeItScreen(total, {}, {}, initialText = total.source)
    }

    private val py = app.cs23c02.companion.runner.CPythonRunner()

    @Test fun playgroundExplainsTheCursorLine() {
        val src = "marks = [72, 85, 64]\nbest = 0\nfor m in marks:\n    if m > best:\n        best = m\nprint(best)"
        val live = kotlinx.coroutines.runBlocking { py.run(src, "") }
        shot("17-playground") {
            app.cs23c02.companion.ui.screens.PlaygroundScreen(py, { _, _ -> }, {}, initialText = "marks = [72, 85, 64]\nbest = 0\nfor m in marks:\n    if m > best:", initialLive = live)
        }
    }

    @Test fun playgroundEmpty() = shot("17-playground-empty") { app.cs23c02.companion.ui.screens.PlaygroundScreen(py, { _, _ -> }, {}) }

    @Test fun playgroundShowsAFriendlyError() {
        val src = "total = 0\nfor m in [1, 2]\n    total += m\n"
        val live = kotlinx.coroutines.runBlocking { py.run(src, "") }
        shot("18-playground-error") { app.cs23c02.companion.ui.screens.PlaygroundScreen(py, { _, _ -> }, {}, initialText = src, initialLive = live) }
    }

    @Test fun liveStepperAsksForInput() {
        val src = "name = input('Your name? ')\nprint('Hello', name)\n"
        val live = kotlinx.coroutines.runBlocking { py.run(src, "") }
        val prog = live.toProgram("Your own code", src, "")
        shot("19-stepper-input") { app.cs23c02.companion.ui.screens.StepperScreenAt(prog, prog.recording.steps.size, onInput = {}) }
    }

    @Test fun welcomeWithPreviewTutor() = shot("20-welcome-preview") {
        WelcomeScreen(AppViewModel(ApiClient("https://api.invalid", InMemoryTokenStore()), "test", demos, previewTutor = true))
    }

    private fun verdict(id: String, correct: Boolean, revealed: Boolean, why: String?, answer: String?, expl: String?) =
        app.cs23c02.companion.api.PracticeVerdict(id, correct, if (correct) 0 else 1, why, revealed, answer, expl)

    @Test fun practiceQuestions() {
        val i1 = samplePkg.item("ITM-9001")
        shot("23-practice-nothing-chosen") { app.cs23c02.companion.ui.screens.PracticeScreen(i1, 0, 2, "What is a variable?", null, false, null, {}, {}, {}, {}, {}, null) }
        shot("23-practice-question") { app.cs23c02.companion.ui.screens.PracticeScreen(i1, 0, 2, "What is a variable?", null, false, null, {}, {}, {}, {}, {}, null, initialChoice = "a") }
        shot("23-practice-wrong") { app.cs23c02.companion.ui.screens.PracticeScreen(i1, 0, 2, "What is a variable?",
            verdict("ITM-9001", false, false, "Comparing uses ==, not =.", null, null), false, null, {}, {}, {}, {}, {}, null, initialChoice = "a") }
        shot("23-practice-revealed-dark", dark = true) { app.cs23c02.companion.ui.screens.PracticeScreen(i1, 0, 2, "What is a variable?",
            verdict("ITM-9001", false, true, "Nothing is printed until print() is called.", "b",
                "SAMPLE: = stores the value on the right under the name on the left. It does not compare and does not print."),
            false, null, {}, {}, {}, {}, {}, null, initialChoice = "c") }
        val tr = samplePkg.item("ITM-9004")
        shot("24-trace-correct") { app.cs23c02.companion.ui.screens.PracticeScreen(tr, 0, 1, "if and else",
            verdict("ITM-9004", true, true, null, "b", "SAMPLE: 4 > 10 is False, so the if block is skipped and the else block runs."),
            false, null, {}, {}, {}, {}, {}, { _ -> }, initialChoice = "b") }
        shot("24-trace-offline") { app.cs23c02.companion.ui.screens.PracticeScreen(tr, 0, 1, "if and else", null, false,
            "Checking answers needs a connection. Reading still works offline.", {}, {}, {}, {}, {}, { _ -> }, initialChoice = "a") }
    }

    @Test @Config(qualifiers = "w360dp-h1280dp-xhdpi") fun exercisesTall() = exercises("-tall")

    @Test fun exercises() = exercises("")

    private fun exercises(suffix: String) {
        val ex = samplePkg.exercise("EXR-9001")
        val A = app.cs23c02.companion.api.TestOutcome("4\n", "8\n", "6\n", false, "ok", null)
        val failed = app.cs23c02.companion.api.ExerciseResult("EXR-9001", false, listOf(A),
            app.cs23c02.companion.api.HiddenSummary(0, 2, "wrong output"),
            app.cs23c02.companion.api.HelpState(1, false, 1, 2, 2, false, 4))
        val hint = app.cs23c02.companion.api.ExerciseHelp(1, false, 1, 2, 2, false, 4,
            listOf("SAMPLE: input() gives text. int() turns it into a number you can multiply."), null)
        val run = kotlinx.coroutines.runBlocking { py.run("n = int(input())\nprint(n + 2)\n", "4\n", echo = false) }
        shot("25-exercise-start$suffix") { app.cs23c02.companion.ui.screens.ExerciseScreen(ex, null, null, null, null, false, false, null, true,
            {}, { _ -> }, {}, {}, {}, {}) }
        shot("25-exercise-failed-hint$suffix") { app.cs23c02.companion.ui.screens.ExerciseScreen(ex, "n = int(input())\nprint(n + 2)\n", failed, hint, run,
            false, false, null, true, {}, { _ -> }, {}, {}, {}, {}) }
        val ok = app.cs23c02.companion.api.ExerciseResult("EXR-9001", true,
            listOf(app.cs23c02.companion.api.TestOutcome("4\n", "8\n", "8\n", true)), app.cs23c02.companion.api.HiddenSummary(2, 2, null),
            app.cs23c02.companion.api.HelpState(1, true, 2, 2, null, true, null))
        val sol = app.cs23c02.companion.api.ExerciseHelp(1, true, 2, 2, null, true, null, listOf("SAMPLE: hint one.", "SAMPLE: hint two."),
            app.cs23c02.companion.api.Solution("SAMPLE: read the number, multiply by 2, print the result.", "n = int(input())\nprint(n * 2)\n"))
        shot("25-exercise-passed-dark$suffix", dark = true) { app.cs23c02.companion.ui.screens.ExerciseScreen(ex, "n = int(input())\nprint(n * 2)\n", ok, sol,
            null, false, false, null, true, {}, { _ -> }, {}, {}, {}, {}) }
        shot("25-exercise-offline$suffix") { app.cs23c02.companion.ui.screens.ExerciseScreen(ex, null, null, null, null, false, false,
            "Checking needs a connection. Run and Step through work offline.", true, {}, { _ -> }, {}, {}, {}, {}) }
    }

    private val asmKeys by lazy {
        app.cs23c02.companion.content.SamplePracticeChecker.parse(java.io.File("src/debug/assets/sample-practice-keys.json").readText()).assessmentKeys
    }
    private val fixedNow = java.time.Instant.parse("2026-11-06T10:00:00Z")

    @Test @Config(qualifiers = "w360dp-h1100dp-xhdpi") fun assessment() {
        val e = app.cs23c02.companion.content.SampleAssessmentEngine({ samplePkg }, asmKeys) { fixedNow }
        val a = e.start("ASM-9001")
        val ui0 = app.cs23c02.companion.ui.AssessmentUi(history = e.history("ASM-9001"))
        shot("26-assessment-intro") { app.cs23c02.companion.ui.screens.AssessmentScreen("Unit 1 assessment", 3,
            ui0.copy(history = app.cs23c02.companion.api.AssessmentHistory("ASM-9001")), {}, { _, _ -> }, {}, {}, {}, {}, {}) }
        val trace = a.questions.indexOfFirst { it.type == "trace" }
        val ui1 = app.cs23c02.companion.ui.AssessmentUi(attempt = a, answers = mapOf(a.questions[0].itemId to a.questions[0].options[0].id),
            seq = 1, index = trace)
        shot("26-assessment-question") { app.cs23c02.companion.ui.screens.AssessmentScreen("Unit 1 assessment", 3, ui1, {}, { _, _ -> }, {}, {},
            {}, {}, {}, now = { fixedNow.plusSeconds(600) }) }
        e.save(a.attemptId, 1, mapOf("ITM-9101" to "c", "ITM-9102" to "a", "ITM-9103" to "b"))
        val done = e.submit(a.attemptId, 1, emptyMap())
        shot("26-assessment-result") { app.cs23c02.companion.ui.screens.AssessmentScreen("Unit 1 assessment", 3,
            app.cs23c02.companion.ui.AssessmentUi(attempt = done), {}, { _, _ -> }, {}, {}, {}, {}, {}) }
        shot("26-assessment-cooldown-dark", dark = true) { app.cs23c02.companion.ui.screens.AssessmentScreen("Unit 1 assessment", 3,
            app.cs23c02.companion.ui.AssessmentUi(history = e.history("ASM-9001")), {}, { _, _ -> }, {}, {}, {}, {}, {}) }
        val best = app.cs23c02.companion.api.ProgressSummary("sample", completed = listOf("LSN-9001", "LSN-9002"),
            exercisesPassed = listOf("EXR-9001"), assessments = e.best())
        shot("27-me") { app.cs23c02.companion.ui.screens.MeScreen("student@example.com", samplePkg,
            readerUi(setOf("LSN-9001", "LSN-9002"), null), app.cs23c02.companion.ui.MeUi(best), false, "0.1.0", { _, _ -> }, {}, {}) }
        shot("21-unit-with-assessment") { app.cs23c02.companion.ui.screens.UnitScreen(samplePkg, "UNT-9001", readerUi(), {}, {}, onAssessment = {}) }
    }

    @Test fun lessonWithPractice() = shot("22-lesson-practise") {
        app.cs23c02.companion.ui.screens.LessonScreen(samplePkg, "LSN-9002", false, 4, { _, _ -> }, {}, {}, {}, {}, false, onPractise = {})
    }

    @Test @Config(fontScale = 2.0f) fun largeFontSignIn() = shot("08-sign-in-200pct-font") { SignInScreen(vm, UiState(Screen.SignIn, error = "Email or password is incorrect.")) }
}
