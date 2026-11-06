package app.cs23c02.companion.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.cs23c02.companion.api.ApiClient
import app.cs23c02.companion.api.ApiError
import app.cs23c02.companion.api.Me
import app.cs23c02.companion.core.ActivationCode
import app.cs23c02.companion.core.Validation
import app.cs23c02.companion.stepper.StepProgram
import app.cs23c02.companion.content.InstalledPackage
import app.cs23c02.companion.content.ProgressStore
import app.cs23c02.companion.content.ReaderRepository
import app.cs23c02.companion.content.lessonIds
import app.cs23c02.companion.content.next
import app.cs23c02.companion.content.place
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
    /** [sample]: debug-only SAMPLE lessons bundled in the APK, no account. */
    data class Home(val me: Me, val sample: Boolean = false) : Screen
    data class UnitPage(val unitId: String, val back: Screen) : Screen
    data class LessonPage(val lessonId: String, val back: Screen) : Screen
    /** Practice for one lesson, one question at a time (screens 9-10). */
    data class PracticePage(val lessonId: String, val index: Int, val back: Screen) : Screen
    /** Screen 11: one programming exercise. */
    data class ExercisePage(val exerciseId: String, val lessonId: String, val back: Screen) : Screen
    /** [start]: step to open at; [live]: recorded on the phone just now (so input can be supplied and re-run). */
    data class Stepper(val program: StepProgram, val back: Screen, val start: Int = 0, val live: Boolean = false) : Screen
    /** Free coding with live explanations (needs on-device Python). */
    data class Playground(val back: Screen, val initial: String = "") : Screen
    /** ADR 0014: ask for a program, then "Show me" or "I'll type it". */
    data class Tutor(val back: Screen) : Screen
    data class TypeIt(val program: StepProgram, val back: Screen) : Screen
}

/** What the reader screens show. [pkg] null = nothing installed yet. */
data class ReaderUi(
    val pkg: InstalledPackage? = null,
    val completed: Set<String> = emptySet(),
    val lastOpened: String? = null,
    val positions: Map<String, Int> = emptyMap(),
    val syncing: Boolean = false,
    val offline: Boolean = false,
    val problem: String? = null,
    val sample: Boolean = false,
) {
    val resume: String? get() = pkg?.let { ProgressStore.resume(it.manifest.nav.lessonIds(), completed, lastOpened) }
}

/** Verdicts received this session, per question. Keys are never stored beyond what the server already revealed. */
data class PracticeUi(val verdicts: Map<String, app.cs23c02.companion.api.PracticeVerdict> = emptyMap(),
                      val checking: Boolean = false, val error: String? = null)

/** Exercise screen state: last graded result, released help, and the last local Run, per exercise. */
data class ExerciseUi(
    val results: Map<String, app.cs23c02.companion.api.ExerciseResult> = emptyMap(),
    val help: Map<String, app.cs23c02.companion.api.ExerciseHelp> = emptyMap(),
    val runs: Map<String, app.cs23c02.companion.runner.LiveResult> = emptyMap(),
    val checking: Boolean = false,
    val running: Boolean = false,
    val error: String? = null,
)

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
    /** Debug builds only: open the tutor from Welcome so it can be tried on a phone without a server. */
    val previewTutor: Boolean = false,
    /** On-device Python (ADR 0013 slice 3); null where unavailable (e.g. JVM tests). */
    val runner: app.cs23c02.companion.runner.PythonRunner? = null,
    /** Lessons + progress (A2). [readerFor] (true) gives the debug SAMPLE package, (false) the account's own. */
    private val readerFor: ((sample: Boolean) -> ReaderRepository)? = null,
) : ViewModel() {
    private val _exercise = MutableStateFlow(ExerciseUi())
    val exercise: StateFlow<ExerciseUi> = _exercise.asStateFlow()
    private val _practice = MutableStateFlow(PracticeUi())
    val practice: StateFlow<PracticeUi> = _practice.asStateFlow()
    private val _reader = MutableStateFlow(ReaderUi())
    val reader: StateFlow<ReaderUi> = _reader.asStateFlow()
    private var repo: ReaderRepository? = null
    private var edition: String? = null
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
    fun openTutor() { runner?.warmUp(); go(Screen.Tutor(_state.value.screen)) }
    fun openTypeIt(id: String) {
        val p = demos.firstOrNull { it.id == id } ?: return
        runner?.warmUp()
        go(Screen.TypeIt(p, _state.value.screen))
    }
    fun openPlayground(initial: String = "") { runner?.warmUp(); go(Screen.Playground(_state.value.screen, initial)) }

    // ---- Reader (A2) ---------------------------------------------------------------------------------------------
    private fun startReader(sample: Boolean, editionId: String?) {
        val factory = readerFor ?: return
        if (repo == null || repo!!.isSample != sample) repo = factory(sample)
        edition = editionId
        publishReader(syncing = true)
        syncReader()
    }

    fun syncReader() {
        val r = repo ?: return
        viewModelScope.launch {
            _reader.update { it.copy(syncing = true, problem = null) }
            val out = r.sync(edition)
            publishReader(syncing = false,
                offline = out is ReaderRepository.SyncOutcome.Offline,
                problem = (out as? ReaderRepository.SyncOutcome.Failed)?.message
                    ?: (if (out is ReaderRepository.SyncOutcome.Offline && !out.hasContent) "You're offline. Connect once to download your lessons." else null))
        }
    }

    private fun publishReader(syncing: Boolean = _reader.value.syncing, offline: Boolean = _reader.value.offline, problem: String? = _reader.value.problem) {
        val r = repo ?: return
        val p = r.progress.state
        _reader.value = ReaderUi(r.content.current(), p.completed, p.lastOpened, p.positions, syncing, offline, problem, r.isSample)
    }

    /** Debug builds only: the bundled SAMPLE lessons, without an account or a server. */
    fun openSampleReader() {
        startReader(sample = true, editionId = null)
        go(Screen.Home(Me("Sample content (test build)", emptyList()), sample = true))
    }

    /** Leaves the sample lessons (no account to sign out of). */
    fun leaveSample() { repo = null; _reader.value = ReaderUi(); go(Screen.Welcome) }

    private fun homeScreen(): Screen {
        var s = _state.value.screen
        while (true) s = when (s) {
            is Screen.Home -> return s
            is Screen.UnitPage -> s.back
            is Screen.LessonPage -> s.back
            is Screen.PracticePage -> s.back
            is Screen.ExercisePage -> s.back
            is Screen.Stepper -> s.back
            is Screen.Playground -> s.back
            is Screen.Tutor -> s.back
            is Screen.TypeIt -> s.back
            else -> return Screen.Welcome
        }
    }

    fun openUnit(unitId: String) = go(Screen.UnitPage(unitId, homeScreen()))

    /** Opening a lesson records it (low-stakes, ADR 0009) and remembers it for Continue. Back goes to the lesson's unit. */
    fun openLesson(lessonId: String) {
        val r = repo ?: return
        val nav = _reader.value.pkg?.manifest?.nav ?: return
        val unit = nav.place(lessonId)?.unit ?: return
        r.progress.opened(lessonId)
        publishReader()
        go(Screen.LessonPage(lessonId, Screen.UnitPage(unit.id, homeScreen())))
        viewModelScope.launch { r.pushProgress(edition) }
    }

    fun saveLessonPosition(lessonId: String, scroll: Int) {
        repo?.progress?.position(lessonId, scroll)
        publishReader()
    }

    fun openPractice(lessonId: String, index: Int = 0) {
        val lesson = _reader.value.pkg?.let { runCatching { it.lesson(lessonId) }.getOrNull() } ?: return
        if (lesson.practice.items.isEmpty()) return
        val back = (_state.value.screen as? Screen.PracticePage)?.back ?: _state.value.screen
        _practice.update { it.copy(error = null) }
        go(Screen.PracticePage(lessonId, index.coerceIn(0, lesson.practice.items.lastIndex), back))
    }

    fun checkPractice(itemId: String, option: String) {
        val r = repo ?: return
        if (_practice.value.checking) return
        _practice.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val v = r.checkPractice(itemId, option, edition)
                _practice.update { it.copy(checking = false, verdicts = it.verdicts + (itemId to v)) }
            } catch (e: ApiError) {
                val msg = when {
                    e.isNetwork -> "Checking answers needs a connection. Reading still works offline."
                    e.status == 429 -> "That's a lot of tries in a short time. Take a break and come back to it."
                    else -> e.message
                }
                _practice.update { it.copy(checking = false, error = msg) }
            } catch (e: app.cs23c02.companion.content.ContentError) {
                _practice.update { it.copy(checking = false, error = e.message) }
            }
        }
    }

    fun openExercise(exerciseId: String, lessonId: String) {
        runner?.warmUp()
        _exercise.update { it.copy(error = null) }
        go(Screen.ExercisePage(exerciseId, lessonId, _state.value.screen))
    }

    fun saveDraft(exerciseId: String, code: String) = repo?.progress?.draft(exerciseId, code)

    fun draftFor(exerciseId: String): String? = repo?.progress?.state?.drafts?.get(exerciseId)

    private fun exerciseError(e: Exception): String = when {
        e is ApiError && e.isNetwork -> "Checking needs a connection. Run and Step through work offline."
        e is ApiError && e.code == "check_in_progress" -> "Your previous check is still running. Wait a moment."
        e is ApiError && e.status == 429 -> "That's a lot of checks in a short time. Take a break and come back to it."
        e is ApiError -> e.message
        else -> e.message ?: "Checking failed."
    }

    /** Check: graded on the server against public and hidden tests. */
    fun checkExercise(ex: app.cs23c02.companion.content.Exercise, code: String) {
        val r = repo ?: return
        if (_exercise.value.checking) return
        saveDraft(ex.id, code)
        _exercise.update { it.copy(checking = true, error = null) }
        viewModelScope.launch {
            try {
                val res = r.submitExercise(ex, code, edition)
                _exercise.update { it.copy(checking = false, results = it.results + (ex.id to res)) }
                if (res.help.hintsUnlocked > 0 || res.help.solutionUnlocked) loadHelp(ex)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _exercise.update { it.copy(checking = false, error = exerciseError(e)) }
            }
        }
    }

    fun loadHelp(ex: app.cs23c02.companion.content.Exercise) {
        val r = repo ?: return
        viewModelScope.launch {
            try {
                val h = r.exerciseHelp(ex, edition)
                _exercise.update { it.copy(help = it.help + (ex.id to h)) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _exercise.update { it.copy(error = exerciseError(e)) }
            }
        }
    }

    /** Run: on the phone with the first example's input. Never graded, never sent anywhere. */
    fun runExercise(ex: app.cs23c02.companion.content.Exercise, code: String) {
        val rr = runner ?: return
        if (_exercise.value.running) return
        saveDraft(ex.id, code)
        _exercise.update { it.copy(running = true, error = null) }
        viewModelScope.launch {
            val out = try { rr.run(code, ex.tests.firstOrNull()?.stdin.orEmpty(), echo = false) } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _exercise.update { it.copy(running = false, error = "Python is not ready yet. Try again in a moment.") }; return@launch
            }
            _exercise.update { it.copy(running = false, runs = it.runs + (ex.id to out)) }
        }
    }

    /** "Try again": forget the last verdict for this question (the server still counts the wrong attempt). */
    fun retryPractice(itemId: String) = _practice.update { p ->
        val v = p.verdicts[itemId]
        if (v != null && !v.revealed) p.copy(verdicts = p.verdicts - itemId) else p
    }

    /** "Done": marks the lesson complete and moves to the next one (or back home after the last). */
    fun completeLesson(lessonId: String) {
        val r = repo ?: return
        val nav = _reader.value.pkg?.manifest?.nav ?: return
        r.progress.completed(lessonId)
        publishReader()
        viewModelScope.launch { r.pushProgress(edition) }
        val next = nav.next(lessonId)
        if (next != null) openLesson(next) else go(homeScreen(), info = "You have finished every lesson. Well done.")
    }

    /** Record the student's own code on the phone and open it in the stepper. */
    fun stepThroughCode(title: String, source: String, stdin: String = "") {
        val r = runner ?: return
        if (_state.value.busy) return
        val from = _state.value.screen
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val res = try { r.run(source, stdin) } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Python is not ready yet. Try again in a moment.") }; return@launch
            }
            go(Screen.Stepper(res.toProgram(title, source, stdin), from, live = true))
        }
    }

    /** The live program stopped at input(): add the student's line and record again, continuing where it paused. */
    fun provideInput(line: String) {
        val st = _state.value.screen as? Screen.Stepper ?: return
        val r = runner ?: return
        if (!st.live || _state.value.busy) return
        val p = st.program
        val stdin = if (p.stdin.isEmpty()) line else p.stdin + "\n" + line
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val res = try { r.run(p.source, stdin) } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = "Python is not ready yet. Try again in a moment.") }; return@launch
            }
            go(Screen.Stepper(res.toProgram(p.title, p.source, stdin), st.back, start = p.recording.steps.size, live = true))
        }
    }

    override fun onCleared() { runner?.close() }

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
            is Screen.Playground -> go(s.back)
            is Screen.UnitPage -> go(s.back)
            is Screen.LessonPage -> go(s.back)
            is Screen.PracticePage -> go(s.back)
            is Screen.ExercisePage -> go(s.back)
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
            repo?.let { if (!it.isSample) it.clear() }
            repo = null
            _reader.value = ReaderUi()
            go(Screen.Welcome)
        }
    }

    fun clearMessages() = _state.update { it.copy(error = null, info = null, fieldErrors = emptyMap()) }

    // ---- Helpers ----------------------------------------------------------------------------------------------
    private suspend fun routeAfterSignIn() {
        val me = api.me()
        if (me.entitlements.isEmpty()) { go(Screen.Activate); return }
        go(Screen.Home(me))
        startReader(sample = false, editionId = me.entitlements.first().editionId)
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
