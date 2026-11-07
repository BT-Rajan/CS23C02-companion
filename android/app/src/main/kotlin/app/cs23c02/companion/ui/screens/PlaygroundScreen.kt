package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cs23c02.companion.runner.LiveResult
import app.cs23c02.companion.runner.PythonRunner
import app.cs23c02.companion.runner.RunnerState
import app.cs23c02.companion.tutor.Verdict
import app.cs23c02.companion.ui.theme.CodeFont
import kotlinx.coroutines.delay

/**
 * Free coding with the tutor alongside (ADR 0014 §5): a short pause in typing runs the code on the phone; the panel then
 * explains the line the cursor is on, cites the book, and points at problems. Nothing leaves the phone.
 */
@Composable
fun PlaygroundScreen(runner: PythonRunner, onStepThrough: (source: String, stdin: String) -> Unit, onClose: () -> Unit,
                     busy: Boolean = false, showTiming: Boolean = false, initialText: String = "", initialLive: LiveResult? = null) {
    var text by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var stdin by rememberSaveable { mutableStateOf("") }
    var live by remember { mutableStateOf(initialLive) }
    val rs by runner.state.collectAsState()

    LaunchedEffect(text.text, stdin, rs is RunnerState.Ready) {
        if (text.text.isBlank() || rs !is RunnerState.Ready) return@LaunchedEffect
        delay(700)  // wait for a pause in typing
        live = runner.run(text.text, stdin)
    }
    val cursorLine = text.text.take(text.selection.start).count { it == '\n' } + 1
    val errLine = live?.recording?.error?.line
    val marks = buildMap { if (errLine != null) put(errLine, Verdict.DIFFERENT) }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your own code", style = MaterialTheme.typography.titleLarge)
                    Text(when (val st = rs) {
                        RunnerState.Starting -> "Getting Python ready…"
                        is RunnerState.Failed -> "Python could not start: ${st.reason}"
                        is RunnerState.Ready -> if (showTiming) "Python ready in %.1f s (%s)%s".format(st.loadMs / 1000.0, st.mode,
                            live?.let { " · last run ${it.elapsedMs} ms" } ?: "") else "Python is ready"
                    }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LivePanel(live, cursorLine, text.text.isBlank())
                Editor(text, marks) { nv -> text = autoIndent(text, nv) }
                OutlinedTextField(stdin, { stdin = it }, label = { Text("Input for the program (one line each)") },
                    minLines = 1, maxLines = 4, modifier = Modifier.fillMaxWidth())
                PrimaryButton("Step through", busy = busy, enabled = rs is RunnerState.Ready && text.text.isNotBlank()) { onStepThrough(text.text, stdin) }
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { text = insertAt(text, "    ") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tab") }
                    SYMBOLS.forEach { sym ->
                        TextButton(onClick = { text = insertAt(text, sym) }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Insert $sym" }) { Text(sym, fontFamily = CodeFont, fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}

internal fun insertAt(v: TextFieldValue, s: String): TextFieldValue {
    val t = v.text
    return TextFieldValue(t.substring(0, v.selection.start) + s + t.substring(v.selection.end), TextRange(v.selection.start + s.length))
}

/** Plain-language versions of the messages beginners meet most often. */
internal fun friendlyError(type: String, message: String): String = when {
    type == "SyntaxError" && message.contains("expected ':'") -> "Add a colon ':' at the end of this line. Lines that start a block (for, if, while, def, else) end with ':'."
    type == "SyntaxError" && (message.contains("was never closed") || message.contains("unmatched")) -> "A bracket or quote is not closed: check that every ( [ \" has its partner."
    type == "SyntaxError" && message.contains("invalid syntax. Maybe you meant '=='") -> "Use == to compare two values. A single = stores a value."
    type == "IndentationError" || message.contains("indent") -> "The spaces at the start of this line don't fit: lines inside a block need 4 more spaces than the line ending with ':'."
    type == "NameError" -> "Python doesn't know this name yet. Check the spelling, or give it a value on an earlier line. ($message)"
    type == "TypeError" -> "These values can't be used together like this. ($message) Tip: input() gives text; use int() for numbers."
    type == "ZeroDivisionError" -> "This divides by zero, which has no answer."
    type == "ValueError" -> "A value has the wrong form. ($message)"
    else -> "$type: $message"
}

@Composable
private fun LivePanel(live: LiveResult?, cursorLine: Int, empty: Boolean) {
    val c = MaterialTheme.colorScheme
    val rec = live?.recording
    val err = rec?.error
    val isProblem = rec != null && rec.status in setOf("error", "syntax_error", "killed")
    Surface(color = if (isProblem) c.errorContainer else c.secondary.copy(alpha = 0.12f),
        contentColor = if (isProblem) c.onErrorContainer else c.onSurface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                empty -> Text("Start typing. Pause for a moment and the tutor explains the line you are on.", style = MaterialTheme.typography.bodyLarge)
                live == null -> Text("Reading your code…", style = MaterialTheme.typography.bodyLarge)
                else -> {
                    if (isProblem && err != null) {
                        Text("Problem${err.line?.let { " on line $it" } ?: ""}", style = MaterialTheme.typography.labelLarge)
                        Text(friendlyError(err.type, err.message), style = MaterialTheme.typography.bodyLarge)
                    }
                    val info = live.lines.firstOrNull { it.n == cursorLine } ?: live.lines.lastOrNull { it.n < cursorLine }
                    if (info != null) {
                        Text("Line ${info.n}", style = MaterialTheme.typography.labelLarge)
                        Text(info.explain, style = MaterialTheme.typography.bodyLarge)
                        info.constructs.mapNotNull { live.refs[it] }.distinctBy { it.topic }.forEach { BookRefRow(it) }
                    }
                    if (!isProblem) {
                        val outcome = when (rec?.status) {
                            "ok" -> "Runs without errors" + (if (rec.stdout.isNotEmpty()) " and prints ${rec.stdout.lines().count { it.isNotEmpty() }} line(s)." else ".")
                            "needs_input" -> "It asks for input: type it in the box below, or step through and answer there."
                            "step_limit" -> "It runs a very long time (more than 2,000 steps). Is a loop missing a way to stop?"
                            else -> null
                        }
                        outcome?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
