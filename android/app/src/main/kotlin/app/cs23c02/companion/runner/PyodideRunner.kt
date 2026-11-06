package app.cs23c02.companion.runner

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pyodide (CPython 3.12 in WebAssembly) inside an invisible, locked-down WebView (ADR 0006 §1, ADR 0013 slice 3):
 * - files come only from the APK's assets via WebViewAssetLoader; every other request gets an empty 403;
 * - network loads are blocked outright, file/content access is off, navigation and new windows are refused;
 * - the page sees one bridge object with three string callbacks and nothing about the app or the student;
 * - a run that does not answer in time is killed: the page terminates its worker, and if even that fails this class
 *   destroys and rebuilds the WebView.
 */
class PyodideRunner(context: Context) : PythonRunner {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<RunnerState>(RunnerState.Starting)
    override val state: StateFlow<RunnerState> = _state.asStateFlow()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger()
    private var web: WebView? = null
    @Volatile private var mode = "worker"

    @Volatile private var startMark = SystemClock.elapsedRealtime()

    /** Loading and warm-up happen in the background (ADR 0006 "warm-up trigger"). */
    override fun warmUp() { main.post { if (web == null) create() } }

    override fun close() {
        main.post { web?.destroy(); web = null; _state.value = RunnerState.Starting }
    }

    override suspend fun run(source: String, stdin: String): LiveResult {
        warmUp()
        val ready = withTimeout(READY_TIMEOUT_MS) { state.first { it is RunnerState.Ready || it is RunnerState.Failed } }
        if (ready is RunnerState.Failed) return failure("Python could not start on this phone: ${ready.reason}")
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<String>()
        pending[id] = answer
        val t0 = SystemClock.elapsedRealtime()
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript("window.runPython($id, ${JsonPrimitive(source)}, ${JsonPrimitive(stdin)}, $RUN_TIMEOUT_MS)", null)
        }
        val text = try {
            withTimeout(RUN_TIMEOUT_MS + APP_GRACE_MS) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            pending.remove(id)
            withContext(Dispatchers.Main) { rebuild() } // the page itself is stuck (in-page fallback mode)
            return failure("The program did not finish in time and was stopped.", status = "killed")
        }
        return LiveResult.parse(text).also { it.elapsedMs = SystemClock.elapsedRealtime() - t0 }
    }

    private fun failure(message: String, status: String = "error") = LiveResult.parse(
        """{"recording":{"version":1,"status":"$status","steps":[],"stdout":"","error":{"type":"Stopped","message":${JsonPrimitive(message)},"line":null}}}""")

    private fun rebuild() {
        web?.destroy()
        web = null
        pending.values.forEach { it.complete(failure("Stopped.", "killed").let { r -> Json.encodeToString(LiveResult.serializer(), r) }) }
        pending.clear()
        _state.value = RunnerState.Starting
        create()
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun create() {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(app)).build()
        startMark = SystemClock.elapsedRealtime()
        web = WebView(app).apply {
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                blockNetworkLoads = true
                domStorageEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                    loader.shouldInterceptRequest(request.url)
                        ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
            }
            addJavascriptInterface(Bridge(), "Bridge")
            loadUrl("https://appassets.androidplatform.net/assets/runner/index.html")
        }
    }

    /** The only things the page can call. Strings in, nothing out. */
    private inner class Bridge {
        @JavascriptInterface fun onReady(ms: String) {
            _state.value = RunnerState.Ready(SystemClock.elapsedRealtime() - startMark, mode)
        }
        @JavascriptInterface fun onResult(id: String, json: String) {
            id.toIntOrNull()?.let { pending.remove(it)?.complete(json) }
        }
        @JavascriptInterface fun onState(s: String) {
            when {
                s.startsWith("mode:") -> mode = s.removePrefix("mode:")
                s.startsWith("failed") -> _state.value = RunnerState.Failed(s.removePrefix("failed: ").take(200))
                s == "starting" -> { startMark = SystemClock.elapsedRealtime(); _state.value = RunnerState.Starting }
            }
        }
    }

    companion object {
        const val RUN_TIMEOUT_MS = 8_000L
        const val APP_GRACE_MS = 4_000L
        const val READY_TIMEOUT_MS = 90_000L
    }
}
