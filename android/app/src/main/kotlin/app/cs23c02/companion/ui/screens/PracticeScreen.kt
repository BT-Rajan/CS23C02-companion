package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.cs23c02.companion.api.PracticeVerdict
import app.cs23c02.companion.content.Item
import app.cs23c02.companion.ui.theme.CodeFont

/** `code` in a question stem is shown in the code font. */
internal fun stemText(stem: String): AnnotatedString = buildAnnotatedString {
    stem.split('`').forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontFamily = CodeFont)) { append(part) } else append(part) }
}

/**
 * Screens 9-10 (A5). Practice teaches: the verdict comes straight after Check, with why that option is wrong; the right
 * answer and full explanation follow a right answer or two wrong tries (server policy). For a trace question the student
 * predicts first; only after that commitment can they step through the program to see why (ADR 0009).
 */
@Composable
fun PracticeScreen(
    item: Item?, index: Int, total: Int, lessonTitle: String, verdict: PracticeVerdict?, checking: Boolean, error: String?,
    onCheck: (String) -> Unit, onRetry: () -> Unit, onNext: () -> Unit, onFinish: () -> Unit, onClose: () -> Unit,
    onStepThrough: ((String) -> Unit)?, initialChoice: String = "",
) {
    var chosen by rememberSaveable(item?.id) { mutableStateOf(initialChoice) }
    val c = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Practice · $lessonTitle", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                    Text("Question ${index + 1} of $total", style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (item == null) { Message("This question could not be opened.", isError = true); return@Column }
            if (item.type == "trace") Text("Read the program and predict what it prints. Then check.",
                style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            Text(stemText(item.stem), style = MaterialTheme.typography.titleMedium)
            item.code?.let { code ->
                Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
                        code.split('\n').forEachIndexed { i, line ->
                            Row { Text("${i + 1}".padStart(2) + "  ", fontFamily = CodeFont, color = c.onSurfaceVariant); Text(line, fontFamily = CodeFont, softWrap = false) }
                        }
                    }
                }
            }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.options.forEach { o ->
                    val picked = chosen == o.id
                    val isAnswer = verdict?.answer == o.id
                    val mark = when { verdict == null -> ""; isAnswer -> "  ✓ right answer"; picked && !verdict.correct -> "  ✗ your choice"; else -> "" }
                    Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (picked || isAnswer) c.primary else c.outline),
                        color = if (isAnswer) c.primaryContainer else c.surface,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .selectable(selected = picked, enabled = verdict == null && !checking, role = Role.RadioButton) { chosen = o.id }) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = picked, onClick = null, enabled = verdict == null)
                            Text(o.text, style = MaterialTheme.typography.bodyLarge,
                                fontFamily = if (item.type == "trace") CodeFont else null, modifier = Modifier.padding(start = 8.dp))
                            if (mark.isNotEmpty()) Text(mark, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            error?.let { Message(it, isError = true) }
            if (verdict == null) {
                PrimaryButton(if (checking) "Checking…" else "Check", busy = checking || chosen.isEmpty()) { onCheck(chosen) }
            } else {
                VerdictCard(verdict)
                if (item.type == "trace" && onStepThrough != null && item.code != null) {
                    OutlinedButton(onClick = { onStepThrough(item.code) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Step through to see why")
                    }
                }
                when {
                    !verdict.correct && !verdict.revealed -> PrimaryButton("Try again", busy = false) { chosen = ""; onRetry() }
                    index + 1 < total -> PrimaryButton("Next question", busy = false, onClick = onNext)
                    else -> PrimaryButton("Finish practice", busy = false, onClick = onFinish)
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(v: PracticeVerdict) {
    val c = MaterialTheme.colorScheme
    Surface(color = if (v.correct) c.primaryContainer else c.errorContainer, contentColor = if (v.correct) c.onPrimaryContainer else c.onErrorContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (v.correct) "✓ Correct" else "✗ Not quite", style = MaterialTheme.typography.titleMedium)
            v.why?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (!v.correct && !v.revealed) Text("Have another go. The answer is shown after your second try.", style = MaterialTheme.typography.bodyMedium)
            v.explanation?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
        }
    }
}
