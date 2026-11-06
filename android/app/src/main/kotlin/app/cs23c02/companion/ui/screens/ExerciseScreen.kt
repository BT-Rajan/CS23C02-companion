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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import app.cs23c02.companion.api.ExerciseHelp
import app.cs23c02.companion.api.ExerciseResult
import app.cs23c02.companion.api.TestOutcome
import app.cs23c02.companion.content.Exercise
import app.cs23c02.companion.runner.LiveResult
import app.cs23c02.companion.ui.theme.CodeFont
import kotlinx.coroutines.delay

private fun show(s: String) = s.trimEnd('\n').ifEmpty { "(nothing)" }

/**
 * Screen 11 (A6). Run and Step through use Python on the phone (fast, offline, never graded). Check sends the code to the
 * server sandbox, which runs the public AND hidden tests. Hints and the solution appear when the server releases them.
 */
@Composable
fun ExerciseScreen(
    ex: Exercise?, draft: String?, result: ExerciseResult?, help: ExerciseHelp?, run: LiveResult?,
    checking: Boolean, running: Boolean, error: String?, canRun: Boolean,
    onRun: (String) -> Unit, onStep: ((String) -> Unit)?, onCheck: (String) -> Unit, onHelp: () -> Unit,
    onDraft: (String) -> Unit, onClose: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val start = draft ?: ex?.starter.orEmpty()
    var text by remember(ex?.id) { mutableStateOf(TextFieldValue(start, TextRange(start.length))) }
    LaunchedEffect(text.text) { delay(800); onDraft(text.text) }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Exercise", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Text(ex?.title ?: "Exercise", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (ex == null) { Message("This exercise could not be opened.", isError = true); return@Column }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(ex.statement, style = MaterialTheme.typography.bodyLarge)
                if (ex.ioSpec.isNotBlank()) Text(ex.ioSpec, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                ex.tests.forEachIndexed { i, t -> ExampleCard(i + 1, t.stdin, t.expect) }
                Editor(text, emptyMap()) { nv -> text = autoIndent(text, nv) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onRun(text.text) }, enabled = canRun && !running && text.text.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(if (running) "Running…" else "Run") }
                    onStep?.let { s -> OutlinedButton(onClick = { s(text.text) }, enabled = text.text.isNotBlank(),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text("Step through") } }
                }
                run?.let { RunOutput(it, ex.tests.firstOrNull()?.stdin) }
                PrimaryButton("Check", busy = checking, enabled = text.text.isNotBlank()) { onCheck(text.text) }
                Text("Check runs your code on the server against the examples and some hidden tests.",
                    style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                error?.let { Message(it, isError = true) }
                result?.let { ResultPanel(it) }
                HelpPanel(result, help, onHelp)
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

@Composable
private fun ExampleCard(n: Int, stdin: String, expect: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Example $n", style = MaterialTheme.typography.labelLarge)
            Text("Input:  ${show(stdin)}", fontFamily = CodeFont)
            Text("Output: ${show(expect)}", fontFamily = CodeFont)
        }
    }
}

@Composable
private fun RunOutput(r: LiveResult, stdin: String?) {
    val rec = r.recording
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your program printed" + (stdin?.let { " (with input ${show(it)})" } ?: ""), style = MaterialTheme.typography.labelLarge)
            Text(rec.stdout.ifEmpty { "(nothing)" }, fontFamily = CodeFont)
            rec.error?.let { Text("Problem: " + friendlyError(it.type, it.message) + (it.line?.let { l -> " (line $l)" } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ResultPanel(r: ExerciseResult) {
    val c = MaterialTheme.colorScheme
    Surface(color = if (r.passed) c.primaryContainer else c.errorContainer, contentColor = if (r.passed) c.onPrimaryContainer else c.onErrorContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (r.passed) "✓ All tests passed" else "✗ Not yet", style = MaterialTheme.typography.titleMedium)
            r.public.forEachIndexed { i, t -> TestLine(i + 1, t) }
            if (r.hidden.total > 0) {
                Text("Hidden tests: ${r.hidden.passed} of ${r.hidden.total} passed" +
                    (r.hidden.firstFailure?.let { ". One $it." } ?: ""), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun TestLine(n: Int, t: TestOutcome) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${if (t.passed) "✓" else "✗"} Example $n", style = MaterialTheme.typography.labelLarge)
        if (!t.passed) {
            Text("Input:     ${show(t.stdin)}", fontFamily = CodeFont)
            Text("Expected:  ${show(t.expect)}", fontFamily = CodeFont)
            Text("Your code: ${show(t.got)}", fontFamily = CodeFont)
            t.error?.let { Text(friendlyErrorLine(it), style = MaterialTheme.typography.bodyMedium) }
            if (t.status == "timeout") Text("It ran too long. Is a loop missing a way to stop?", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** "ZeroDivisionError: division by zero" -> the plain-language message. */
internal fun friendlyErrorLine(line: String): String {
    val type = line.substringBefore(':').trim()
    val msg = line.substringAfter(':', "").trim()
    return friendlyError(type, msg)
}

@Composable
private fun HelpPanel(r: ExerciseResult?, h: ExerciseHelp?, onHelp: () -> Unit) {
    val state = r?.help
    val c = MaterialTheme.colorScheme
    val unlocked = h?.hintsUnlocked ?: state?.hintsUnlocked ?: 0
    val total = h?.hintsTotal ?: state?.hintsTotal ?: 0
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (h != null && h.hints.isNotEmpty()) {
            h.hints.forEachIndexed { i, hint ->
                Surface(color = c.secondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Text("Hint ${i + 1}: $hint", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else if (unlocked > 0) {
            OutlinedButton(onClick = onHelp, modifier = Modifier.heightIn(min = 48.dp)) { Text("Show hint") }
        }
        h?.solution?.let { s ->
            Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("A worked solution", style = MaterialTheme.typography.labelLarge)
                    if (s.approach.isNotBlank()) Text(s.approach, style = MaterialTheme.typography.bodyLarge)
                    Text(s.code.trimEnd(), fontFamily = CodeFont)
                }
            }
        }
        val next = h?.nextHintIn ?: state?.nextHintIn
        val sol = h?.solutionIn ?: state?.solutionIn
        val notes = listOfNotNull(
            next?.takeIf { unlocked < total }?.let { "Next hint after $it more check${if (it == 1) "" else "s"}." },
            sol?.takeIf { h?.solution == null }?.let { "A worked solution after $it more check${if (it == 1) "" else "s"}, or once you pass." },
        )
        if (r != null && notes.isNotEmpty()) Text(notes.joinToString(" "), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
    }
}
