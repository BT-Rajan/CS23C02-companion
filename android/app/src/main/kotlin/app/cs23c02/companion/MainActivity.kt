package app.cs23c02.companion

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.auth.KeystoreTokenStore
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.Screen
import app.cs23c02.companion.ui.screens.ReverifyScreen
import app.cs23c02.companion.ui.screens.ActivateScreen
import app.cs23c02.companion.ui.screens.CreateAccountScreen
import app.cs23c02.companion.ui.screens.ForgotScreen
import app.cs23c02.companion.ui.screens.HomeScreen
import app.cs23c02.companion.ui.screens.LoadingScreen
import app.cs23c02.companion.ui.screens.OfflineScreen
import app.cs23c02.companion.ui.screens.ResetScreen
import app.cs23c02.companion.ui.screens.SignInScreen
import app.cs23c02.companion.ui.screens.VerifyEmailScreen
import app.cs23c02.companion.ui.screens.WelcomeScreen
import app.cs23c02.companion.ui.screens.StepperScreenAt
import app.cs23c02.companion.ui.screens.PlaygroundScreen
import app.cs23c02.companion.runner.PyodideRunner
import app.cs23c02.companion.content.ContentStore
import app.cs23c02.companion.content.ProgressStore
import app.cs23c02.companion.content.ReaderRepository
import app.cs23c02.companion.content.next
import app.cs23c02.companion.ui.screens.LessonScreen
import app.cs23c02.companion.ui.screens.PracticeScreen
import app.cs23c02.companion.ui.screens.ExerciseScreen
import app.cs23c02.companion.ui.screens.AssessmentScreen
import app.cs23c02.companion.ui.screens.MeScreen
import app.cs23c02.companion.ui.screens.UnitScreen
import java.io.File
import app.cs23c02.companion.ui.screens.TutorScreen
import app.cs23c02.companion.ui.screens.TypeItScreen
import app.cs23c02.companion.stepper.Recordings
import app.cs23c02.companion.ui.theme.CompanionTheme

object AppInfo {
    const val NAME = "CS23C02 Companion"
}

/** The server this build talks to. Fixed at build time from API_BASE_URL (never user-editable). */
object ApiConfig {
    val BASE_URL: String = BuildConfig.API_BASE_URL
    val HOST: String = BASE_URL.removePrefix("https://")
    val CERT_PINS: List<String> = BuildConfig.API_CERT_PINS.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}

class MainActivity : ComponentActivity() {
    /** A book QR code (cs23c02://o/<ID>) handed to the ViewModel once the UI is up; also set by onNewIntent. */
    private val link = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        link.value = app.cs23c02.companion.content.DeepLink.parse(intent.dataString)
    }

    /** Lessons live in no-backup app storage; the account's copy is wiped on sign-out (attack C5). */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) link.value = app.cs23c02.companion.content.DeepLink.parse(intent?.dataString)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val api = ApiClient(ApiConfig.BASE_URL, KeystoreTokenStore(applicationContext),
                    ApiClient.defaultHttp(ApiConfig.BASE_URL, ApiConfig.CERT_PINS))
                val demos = runCatching {
                    Recordings.parsePrograms(assets.open("stepper/demos.json").bufferedReader().use { it.readText() })
                }.getOrDefault(emptyList())
                // Only the application context goes into the ViewModel: it outlives this Activity (e.g. on rotation).
                val appCtx = applicationContext
                val runner = PyodideRunner(appCtx)
                return AppViewModel(api, "${Build.MANUFACTURER} ${Build.MODEL}".take(80), demos, previewTutor = BuildConfig.DEBUG,
                    runner = runner, readerFor = { sample -> buildReader(appCtx, api, sample, runner) }) as T
            }
        }
        setContent {
            CompanionTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: AppViewModel = viewModel(factory = factory)
                    val s by vm.state.collectAsStateWithLifecycle()
                    val reader by vm.reader.collectAsStateWithLifecycle()
                    val practice by vm.practice.collectAsStateWithLifecycle()
                    val exercise by vm.exercise.collectAsStateWithLifecycle()
                    val assessment by vm.assessment.collectAsStateWithLifecycle()
                    val me by vm.me.collectAsStateWithLifecycle()
                    val pending by link.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(pending) { pending?.let { vm.openLink(it); link.value = null } }
                    val canGoBack = s.screen is Screen.CreateAccount || s.screen is Screen.SignIn || s.screen is Screen.VerifyEmail ||
                        s.screen is Screen.Forgot || s.screen is Screen.Reset || s.screen is Screen.Stepper ||
                        s.screen is Screen.Tutor || s.screen is Screen.TypeIt || s.screen is Screen.Playground ||
                        s.screen is Screen.UnitPage || s.screen is Screen.LessonPage || s.screen is Screen.PracticePage || s.screen is Screen.ExercisePage ||
                        s.screen is Screen.AssessmentPage || s.screen is Screen.MePage
                    BackHandler(enabled = canGoBack) { vm.back() }
                    Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
                        when (val sc = s.screen) {
                            Screen.Loading -> LoadingScreen()
                            Screen.Offline -> OfflineScreen(vm)
                            Screen.Welcome -> WelcomeScreen(vm)
                            Screen.CreateAccount -> CreateAccountScreen(vm, s)
                            is Screen.VerifyEmail -> VerifyEmailScreen(vm, s, sc.email)
                            Screen.SignIn -> SignInScreen(vm, s)
                            is Screen.Forgot -> ForgotScreen(vm, s, sc.email)
                            is Screen.Reset -> ResetScreen(vm, s, sc.email)
                            Screen.Activate -> ActivateScreen(vm, s)
                            is Screen.Reverify -> ReverifyScreen(vm, s, sc.emailHint)
                            is Screen.Home -> HomeScreen(sc.me, sc.sample, reader, s.info,
                                onContinue = vm::openLesson, onUnit = vm::openUnit, onRetry = vm::syncReader,
                                onTutor = if (vm.demos.isNotEmpty()) ({ vm.openTutor() }) else null,
                                onSignOut = if (sc.sample) vm::leaveSample else vm::signOut, onMe = vm::openMe)
                            is Screen.UnitPage -> reader.pkg?.let { UnitScreen(it, sc.unitId, reader, onLesson = vm::openLesson, onClose = { vm.back() },
                                onAssessment = { aid -> vm.openAssessment(aid, sc.unitId) }) }
                            is Screen.AssessmentPage -> {
                                val asm = reader.pkg?.let { runCatching { it.assessment(sc.assessmentId) }.getOrNull() }
                                AssessmentScreen(asm?.title ?: "Unit assessment", asm?.items?.size ?: 0, assessment,
                                    onStart = { vm.startAssessment(sc.assessmentId) }, onChoose = vm::chooseAnswer, onGoto = vm::gotoQuestion,
                                    onSubmit = vm::submitAssessment, onReview = vm::reviewAttempt,
                                    onBackToIntro = { vm.backToAssessmentIntro(sc.assessmentId) }, onClose = { vm.back() })
                            }
                            is Screen.MePage -> {
                                val home = sc.back as? Screen.Home
                                MeScreen(home?.me?.email.orEmpty(), reader.pkg, reader, me, home?.sample == true, BuildConfig.VERSION_NAME,
                                    onAssessment = { aid, uid -> vm.openAssessment(aid, uid) },
                                    onSignOut = if (home?.sample == true) vm::leaveSample else vm::signOut, onClose = { vm.back() })
                            }
                            is Screen.LessonPage -> reader.pkg?.let { pkg ->
                                LessonScreen(pkg, sc.lessonId, done = sc.lessonId in reader.completed, startAt = reader.positions[sc.lessonId] ?: 0,
                                    onStep = vm.runner?.let { { t: String, c: String -> vm.stepThroughCode(t, c) } },
                                    onTryIt = vm.runner?.let { { c: String -> vm.openPlayground(c) } },
                                    onPosition = { vm.saveLessonPosition(sc.lessonId, it) }, onDone = { vm.completeLesson(sc.lessonId) },
                                    onClose = { vm.back() }, isLast = pkg.manifest.nav.next(sc.lessonId) == null,
                                    onPractise = { vm.openPractice(sc.lessonId) },
                                    onExercise = { id -> vm.openExercise(id, sc.lessonId) })
                            }
                            is Screen.ExercisePage -> reader.pkg?.let { pkg ->
                                val ex = runCatching { pkg.exercise(sc.exerciseId) }.getOrNull()
                                ExerciseScreen(ex, vm.draftFor(sc.exerciseId), exercise.results[sc.exerciseId], exercise.help[sc.exerciseId],
                                    exercise.runs[sc.exerciseId], exercise.checking, exercise.running, exercise.error, canRun = vm.runner != null,
                                    onRun = { code -> ex?.let { vm.runExercise(it, code) } },
                                    onStep = vm.runner?.let { { code: String -> vm.stepThroughCode(ex?.title ?: "Your code", code, ex?.tests?.firstOrNull()?.stdin.orEmpty()) } },
                                    onCheck = { code -> ex?.let { vm.checkExercise(it, code) } }, onHelp = { ex?.let { vm.loadHelp(it) } },
                                    onDraft = { code -> vm.saveDraft(sc.exerciseId, code) }, onClose = { vm.back() })
                            }
                            is Screen.PracticePage -> reader.pkg?.let { pkg ->
                                val lesson = runCatching { pkg.lesson(sc.lessonId) }.getOrNull()
                                val ids = lesson?.practice?.items.orEmpty()
                                val itemId = ids.getOrNull(sc.index)
                                val item = itemId?.let { runCatching { pkg.item(it) }.getOrNull() }
                                PracticeScreen(item, sc.index, ids.size, lesson?.title.orEmpty(), practice.verdicts[itemId], practice.checking,
                                    practice.error, onCheck = { o -> itemId?.let { vm.checkPractice(it, o) } },
                                    onRetry = { itemId?.let { vm.retryPractice(it) } },
                                    onNext = { vm.openPractice(sc.lessonId, sc.index + 1) }, onFinish = { vm.back() }, onClose = { vm.back() },
                                    onStepThrough = vm.runner?.let { { code: String -> vm.stepThroughCode("Why: ${item?.stem.orEmpty()}", code) } })
                            }
                            is Screen.Stepper -> StepperScreenAt(sc.program, sc.start, onClose = { vm.back() },
                                onInput = if (sc.live) vm::provideInput else null, busy = s.busy, error = s.error)
                            is Screen.Tutor -> TutorScreen(vm.demos, onShowMe = vm::openStepper, onTypeIt = vm::openTypeIt, onClose = { vm.back() },
                                onOwnCode = vm.runner?.let { { vm.openPlayground() } })
                            is Screen.TypeIt -> TypeItScreen(sc.program, onStepThrough = { vm.openStepper(sc.program.id) }, onClose = { vm.back() },
                                onRunMine = vm.runner?.let { { src: String -> vm.stepThroughCode("Your version of ${sc.program.title}", src) } }, busy = s.busy)
                            is Screen.Playground -> PlaygroundScreen(vm.runner!!, onStepThrough = { src, inp -> vm.stepThroughCode("Your own code", src, inp) },
                                onClose = { vm.back() }, busy = s.busy, showTiming = BuildConfig.DEBUG, initialText = sc.initial)
                        }
                    }
                }
            }
        }
    }
}
