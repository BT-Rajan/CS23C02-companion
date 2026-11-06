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
    /** Lessons live in no-backup app storage; the account's copy is wiped on sign-out (attack C5). */
    private fun reader(api: ApiClient, sample: Boolean): ReaderRepository {
        val base = File(noBackupFilesDir, if (sample) "sample" else "content")
        val key = if (sample) SampleContent.PUBLIC_KEY else BuildConfig.CONTENT_SIGNING_PUBKEY
        val store = ContentStore(File(base, "package"), key, BuildConfig.VERSION_NAME)
        val progress = ProgressStore(File(base, "progress.json"))
        val loader: (suspend () -> Pair<ByteArray, Map<String, ByteArray>>)? = if (!sample) null else ({
            val dir = SampleContent.ASSET_DIR
            val manifest = assets.open("$dir/manifest.json").use { it.readBytes() }
            val sig = assets.open("$dir/manifest.sig").use { it.readBytes() }.decodeToString().trim()
            // Paths come from the (not yet verified) manifest only to know what to read; install() verifies everything.
            val paths = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString(app.cs23c02.companion.content.Manifest.serializer(), manifest.decodeToString()).files.map { it.path }
            (sig + "\n").toByteArray() + manifest to paths.associateWith { p -> assets.open("$dir/$p").use { it.readBytes() } }
        })
        return ReaderRepository(api, store, progress, loader)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val api = ApiClient(ApiConfig.BASE_URL, KeystoreTokenStore(applicationContext),
                    ApiClient.defaultHttp(ApiConfig.BASE_URL, ApiConfig.CERT_PINS))
                val demos = runCatching {
                    Recordings.parsePrograms(assets.open("stepper/demos.json").bufferedReader().use { it.readText() })
                }.getOrDefault(emptyList())
                return AppViewModel(api, "${Build.MANUFACTURER} ${Build.MODEL}".take(80), demos, previewTutor = BuildConfig.DEBUG,
                    runner = PyodideRunner(applicationContext), readerFor = { sample -> reader(api, sample) }) as T
            }
        }
        setContent {
            CompanionTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: AppViewModel = viewModel(factory = factory)
                    val s by vm.state.collectAsStateWithLifecycle()
                    val reader by vm.reader.collectAsStateWithLifecycle()
                    val canGoBack = s.screen is Screen.CreateAccount || s.screen is Screen.SignIn || s.screen is Screen.VerifyEmail ||
                        s.screen is Screen.Forgot || s.screen is Screen.Reset || s.screen is Screen.Stepper ||
                        s.screen is Screen.Tutor || s.screen is Screen.TypeIt || s.screen is Screen.Playground ||
                        s.screen is Screen.UnitPage || s.screen is Screen.LessonPage
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
                            is Screen.Home -> HomeScreen(sc.me, sc.sample, reader, s.info,
                                onContinue = vm::openLesson, onUnit = vm::openUnit, onRetry = vm::syncReader,
                                onTutor = if (vm.demos.isNotEmpty()) ({ vm.openTutor() }) else null,
                                onSignOut = if (sc.sample) vm::leaveSample else vm::signOut)
                            is Screen.UnitPage -> reader.pkg?.let { UnitScreen(it, sc.unitId, reader, onLesson = vm::openLesson, onClose = { vm.back() }) }
                            is Screen.LessonPage -> reader.pkg?.let { pkg ->
                                LessonScreen(pkg, sc.lessonId, done = sc.lessonId in reader.completed, startAt = reader.positions[sc.lessonId] ?: 0,
                                    onStep = vm.runner?.let { { t: String, c: String -> vm.stepThroughCode(t, c) } },
                                    onTryIt = vm.runner?.let { { c: String -> vm.openPlayground(c) } },
                                    onPosition = { vm.saveLessonPosition(sc.lessonId, it) }, onDone = { vm.completeLesson(sc.lessonId) },
                                    onClose = { vm.back() }, isLast = pkg.manifest.nav.next(sc.lessonId) == null)
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
