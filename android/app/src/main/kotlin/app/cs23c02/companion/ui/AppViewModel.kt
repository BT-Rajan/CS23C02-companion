package app.cs23c02.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.api.ApiError
import app.cs23c02.companion.api.Me
import app.cs23c02.companion.core.ActivationCode
import app.cs23c02.companion.core.Validation
import app.cs23c02.companion.stepper.StepProgram
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screens 1–6 of docs/ux/screen-map.md. Home is the A1 placeholder until the reader (A2) lands. */
sealed interface Screen {
    data object Loading : Screen
    data object Offline : Screen
    data object Welcome : Screen
    data object CreateAccount : Screen
    data class VerifyEmail(val email: String) : Screen
    data object SignIn : Screen
    data class Forgot(val email: String) : Screen
    data class Reset(val email: String) : Screen
    data object Activate : Screen
    data class Home(val me: Me) : Screen
    data class Stepper(val program: StepProgram, val back: Screen) : Screen
    /** ADR 0014: ask for a program, then "Show me" or "I'll type it". */
    data class Tutor(val back: Screen) : Screen
    data class TypeIt(val program: StepProgram, val back: Screen) : Screen
}

data class UiState(
    val screen: Screen = Screen.Loading,
    val busy: Boolean = false,
    /** Problem with the last action, shown near the primary button. */
    val error: String? = null,
    /** Neutral confirmation, e.g. "We sent a new code". */
    val info: String? = null,
    /** Field-level problems keyed by field name ("email", "password", "code"). */
    val fieldErrors: Map<String, String> = emptyMap(),
    /** Prefill for Sign in after a password reset. */
    val lastEmail: String = "",
)

class AppViewModel(
    private val api: ApiClient,
    private val deviceLabel: String,
    /** Built-in stepper demos (assets/stepper/demos.json) until lesson content arrives (A2/A4). */
    val demos: List<StepProgram> = emptyList(),
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Held only in memory, only between Create account and Verify, so "Send a new code" can re-register. */
    private var pendingPassword: String? = null

    init { start() }

    fun start() {
        if (!api.hasSession()) { go(Screen.Welcome); return }
        go(Screen.Loading)
        run(onNetwork = { go(Screen.Offline) }) { routeAfterSignIn() }
    }

    // ---- Navigation -------------------------------------------------------------------------------------------
    fun openCreateAccount() = go(Screen.CreateAccount)
    fun openSignIn() = go(Screen.SignIn)
    fun openForgot(email: String) = go(Screen.Forgot(email.trim()))
    fun openStepper(id: String) {
        val p = demos.firstOrNull { it.id == id } ?: return
        go(Screen.Stepper(p, _state.value.screen))
    }
    fun openTutor() = go(Screen.Tutor(_state.value.screen))
    fun openTypeIt(id: String) {
        val p = demos.firstOrNull { it.id == id } ?: return
        go(Screen.TypeIt(p, _state.value.screen))
    }

    /** System back. Returns false when the app should close. */
    fun back(): Boolean {
        val s = _state.value.screen
        if (_state.value.busy) return true
        when (s) {
            Screen.CreateAccount, Screen.SignIn -> go(Screen.Welcome)
            is Screen.VerifyEmail -> { pendingPassword = null; go(Screen.Welcome) }
            is Screen.Forgot -> go(Screen.SignIn)
            is Screen.Reset -> go(Screen.Forgot(s.email))
            is Screen.Stepper -> go(s.back)
            is Screen.Tutor -> go(s.back)
            is Screen.TypeIt -> go(s.back)
            else -> return false
        }
        return true
    }

    // ---- Actions ----------------------------------------------------------------------------------------------
    fun createAccount(emailRaw: String, password: String) {
        val email = emailRaw.trim()
        val errs = buildMap {
            Validation.emailError(email)?.let { put("email", it) }
            Validation.passwordError(password, email)?.let { put("password", it) }
        }
        if (errs.isNotEmpty()) { _state.update { it.copy(fieldErrors = errs, error = null) }; return }
        run {
            api.register(email, password)
            pendingPassword = password
            go(Screen.VerifyEmail(email), info = "We sent a 6-digit code to $email.")
        }
    }

    fun verify(email: String, code: String) {
        Validation.codeError(code)?.let { e -> _state.update { it.copy(fieldErrors = mapOf("code" to e), error = null) }; return }
        run {
            api.verifyEmail(email, code, deviceLabel)
            pendingPassword = null
            routeAfterSignIn()
        }
    }

    fun resendCode(email: String) {
        val pw = pendingPassword
        if (pw == null) { go(Screen.SignIn, info = "Sign in to get a new code.", lastEmail = email); return }
        run {
            api.register(email, pw)
            _state.update { it.copy(busy = false, info = "A new code is on its way. If you can't see it, check your spam folder.", error = null) }
        }
    }

    fun signIn(emailRaw: String, password: String) {
        val email = emailRaw.trim()
        Validation.emailError(email)?.let { e -> _state.update { it.copy(fieldErrors = mapOf("email" to e), error = null) }; return }
        if (password.isEmpty()) { _state.update { it.copy(fieldErrors = mapOf("password" to "Enter your password."), error = null) }; return }
        run(onError = { e ->
            if (e.code == "email_unverified") { pendingPassword = password; go(Screen.VerifyEmail(email), info = e.message); true } else false
        }) {
            api.login(email, password, deviceLabel)
            routeAfterSignIn()
        }
    }

    fun sendResetCode(emailRaw: String) {
        val email = emailRaw.trim()
        Validation.emailError(email)?.let { e -> _state.update { it.copy(fieldErrors = mapOf("email" to e), error = null) }; return }
        run {
            api.forgot(email)
            go(Screen.Reset(email), info = "If an account uses $email, we sent it a 6-digit code.")
        }
    }

    fun resetPassword(email: String, code: String, newPassword: String) {
        val errs = buildMap {
            Validation.codeError(code)?.let { put("code", it) }
            Validation.passwordError(newPassword, email)?.let { put("password", it) }
        }
        if (errs.isNotEmpty()) { _state.update { it.copy(fieldErrors = errs, error = null) }; return }
        run {
            api.reset(email, code, newPassword)
            go(Screen.SignIn, info = "Password changed. Sign in with your new password.", lastEmail = email)
        }
    }

    fun activate(raw: String) {
        val code = ActivationCode.clean(raw)
        val err = when {
            code.length < ActivationCode.LENGTH -> "Enter all 16 characters."
            !ActivationCode.checksumOk(code) -> "That code doesn't look right. Check each character."
            else -> null
        }
        if (err != null) { _state.update { it.copy(fieldErrors = mapOf("code" to err), error = null) }; return }
        run(onError = { e ->
            if (e.code == "already_activated") { viewModelScope.launch { safeRoute() }; true } else false
        }) {
            api.activate(ActivationCode.pretty(code))
            routeAfterSignIn()
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            api.logout()
            go(Screen.Welcome)
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null, fieldErrors = emptyMap()) }

    // ---- Helpers ----------------------------------------------------------------------------------------------
    private suspend fun routeAfterSignIn() {
        val me = api.me()
        go(if (me.entitlements.isEmpty()) Screen.Activate else Screen.Home(me))
    }

    private suspend fun safeRoute() = try { routeAfterSignIn() } catch (e: ApiError) { fail(e) }

    private fun go(screen: Screen, info: String? = null, lastEmail: String? = null) = _state.update {
        UiState(screen = screen, info = info, lastEmail = lastEmail ?: it.lastEmail)
    }

    private fun fail(e: ApiError) {
        if (e.isUnauthenticated && e.code == "unauthenticated" && api.hasSession().not() && _state.value.screen !is Screen.SignIn) {
            go(Screen.SignIn, info = e.message); return
        }
        val msg = if (e.status == 429 && e.retryAfterSeconds != null) "${e.message} (about ${minutes(e.retryAfterSeconds)})" else e.message
        _state.update { it.copy(busy = false, error = msg, info = null) }
    }

    private fun minutes(s: Int) = if (s < 90) "a minute" else "${(s + 59) / 60} minutes"

    /** Runs one action at a time; [onError] may handle an error itself by returning true. */
    private fun run(onNetwork: (() -> Unit)? = null, onError: ((ApiError) -> Boolean)? = null, block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: ApiError) {
                when {
                    e.isNetwork && onNetwork != null -> onNetwork()
                    onError?.invoke(e) == true -> Unit
                    else -> fail(e)
                }
            }
        }
    }
}
