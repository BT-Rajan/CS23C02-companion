package app.cs23c02.companion.runner

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Test double that runs the SAME runtime/tutor/live.py the phone runs, but on the build machine's CPython (>= 3.12).
 * So JVM tests exercise real recordings and explanations, not canned JSON.
 */
class CPythonRunner(private val python: String = System.getenv("PYTHON") ?: "python3") : PythonRunner {
    private val runtime = File("../../runtime").canonicalFile
    override val state: StateFlow<RunnerState> = MutableStateFlow(RunnerState.Ready(0, "cpython"))
    var runs = 0
    override fun warmUp() {}

    override suspend fun run(source: String, stdin: String, echo: Boolean): LiveResult {
        runs++
        val script = "import sys, json; sys.path[:0] = ['${runtime}/stepper', '${runtime}/tutor']; import live; " +
            "d = json.load(sys.stdin); print(live.run(d['source'], d['stdin'], d['echo']))"
        val p = ProcessBuilder(python, "-I", "-c", script).redirectErrorStream(false).start()
        p.outputStream.use { it.write(buildJsonObject { put("source", source); put("stdin", stdin); put("echo", echo) }.toString().toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { p.errorStream.bufferedReader().readText() }
        return LiveResult.parse(out.trim())
    }
}
