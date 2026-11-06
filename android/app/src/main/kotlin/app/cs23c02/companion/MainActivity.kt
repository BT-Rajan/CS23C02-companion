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
import app.cs23c02.companion.ui.screens.StepperScreen
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
                return AppViewModel(api, "${Build.MANUFACTURER} ${Build.MODEL}".take(80), demos) as T
            }
        }
        setContent {
            CompanionTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val vm: AppViewModel = viewModel(factory = factory)
                    val s by vm.state.collectAsStateWithLifecycle()
                    val canGoBack = s.screen is Screen.CreateAccount || s.screen is Screen.SignIn || s.screen is Screen.VerifyEmail ||
                        s.screen is Screen.Forgot || s.screen is Screen.Reset || s.screen is Screen.Stepper ||
                        s.screen is Screen.Tutor || s.screen is Screen.TypeIt
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
                            is Screen.Home -> HomeScreen(vm, s, sc.me)
                            is Screen.Stepper -> StepperScreen(sc.program) { vm.back() }
                            is Screen.Tutor -> TutorScreen(vm.demos, onShowMe = vm::openStepper, onTypeIt = vm::openTypeIt, onClose = { vm.back() })
                            is Screen.TypeIt -> TypeItScreen(sc.program, onStepThrough = { vm.openStepper(sc.program.id) }, onClose = { vm.back() })
                        }
                    }
                }
            }
        }
    }
}
