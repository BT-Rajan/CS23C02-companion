package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cs23c02.companion.stepper.Frame
import app.cs23c02.companion.stepper.StepProgram
import app.cs23c02.companion.stepper.StepperState
import app.cs23c02.companion.ui.theme.CodeFont
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.5f, 1f, 2f, 4f) // lines per second (ADR 0013)

/**
 * ADR 0013 stepper. One primary action (Step). The current line carries a "▶" marker as well as a highlight, and the
 * line that just ran carries "·", so position never depends on colour alone. Changed variables are marked "changed"
 * in text (no animation, so the reduce-motion setting is respected by design).
 */
@Composable
fun StepperScreen(program: StepProgram, onClose: () -> Unit) = StepperScreenAt(program, 0, onClose)

/** [start] lets previews and screenshot tests open the stepper at a given step. */
@Composable
fun StepperScreenAt(program: StepProgram, start: Int, onClose: () -> Unit = {}) {
    var position by rememberSaveable(program.id) { mutableIntStateOf(start) }
    var playing by rememberSaveable { mutableStateOf(false) }
    var speedIndex by rememberSaveable { mutableIntStateOf(1) }
    val s = StepperState(program, position)

    LaunchedEffect(playing, speedIndex, position) {
        if (!playing) return@LaunchedEffect
        if (s.atEnd) { playing = false; return@LaunchedEffect }
        delay((1000 / SPEEDS[speedIndex]).toLong())
        position = s.next().position
    }

    // Code, explanation and variables scroll together; the controls stay pinned at the bottom so the student can keep
    // pressing Step while watching code and variables on a small screen.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(program.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CodeView(program.source, s.currentLine, s.previousLine)
                Text(
                    s.ending ?: s.narration,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                VariablesPanel(s.frames, s.changed, s.step?.value?.let { "${s.frames.lastOrNull()?.name} returns ${it.r}" })
                s.note?.let { NoteCard(it) }
                OutputPanel(s.outputSoFar)
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Controls(s, playing, SPEEDS[speedIndex],
                        onStep = { playing = false; position = s.next().position },
                        onBack = { playing = false; position = s.back().position },
                        onPlay = { if (s.atEnd) position = 0; playing = !playing },
                        onRestart = { playing = false; position = 0 },
                        onSpeed = { speedIndex = (speedIndex + 1) % SPEEDS.size },
                        onSeek = { playing = false; position = s.seek(it).position })
                }
            }
        }
    }
}

@Composable
private fun CodeView(source: String, current: Int?, previous: Int?) {
    val c = MaterialTheme.colorScheme
    val lines = source.trimEnd('\n').split('\n')
    Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp).width(IntrinsicSize.Max)) {
            lines.forEachIndexed { i, text ->
                val n = i + 1
                val isCurrent = n == current
                val marker = when (n) { current -> "▶"; previous -> "·"; else -> " " }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .widthIn(min = 320.dp)
                        .background(if (isCurrent) c.primaryContainer else c.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .semantics {
                            contentDescription = "Line $n: ${text.trim()}"
                            if (isCurrent) stateDescription = "runs next" else if (n == previous) stateDescription = "just ran"
                        },
                ) {
                    Text(marker, fontFamily = CodeFont, color = c.primary, modifier = Modifier.width(16.dp))
                    Text(n.toString().padStart(2), fontFamily = CodeFont, color = c.onSurfaceVariant, modifier = Modifier.width(28.dp))
                    Text(text, fontFamily = CodeFont, color = if (isCurrent) c.onPrimaryContainer else c.onSurface,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal, softWrap = false)
                }
            }
        }
    }
}

@Composable
private fun NoteCard(text: String) {
    Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Text("Note: $text", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Controls(
    s: StepperState, playing: Boolean, speed: Float,
    onStep: () -> Unit, onBack: () -> Unit, onPlay: () -> Unit, onRestart: () -> Unit, onSpeed: () -> Unit, onSeek: (Int) -> Unit,
) {
    var dragging by rememberSaveable { mutableStateOf(false) }
    var dragValue by rememberSaveable { mutableFloatStateOf(0f) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, enabled = !s.atStart, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
            Button(onClick = onStep, enabled = !s.atEnd, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Step") }
            OutlinedButton(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (playing) "Pause" else "Play") }
        }
        Slider(
            value = if (dragging) dragValue else s.position.toFloat(),
            onValueChange = { dragging = true; dragValue = it; onSeek(it.toInt()) },
            onValueChangeFinished = { dragging = false },
            valueRange = 0f..s.total.coerceAtLeast(1).toFloat(),
            steps = (s.total - 1).coerceIn(0, 200),
            modifier = Modifier.semantics { contentDescription = "Step ${s.position} of ${s.total}" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Step ${s.position} of ${s.total}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onSpeed) { Text("Speed ${if (speed < 1) "½" else speed.toInt().toString()}×") }
            TextButton(onClick = onRestart, enabled = !s.atStart) { Text("Restart") }
        }
    }
}

@Composable
private fun VariablesPanel(frames: List<Frame>, changed: Set<String>, returning: String?) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Variables", style = MaterialTheme.typography.titleMedium)
        if (frames.isEmpty()) Text("None yet.", color = c.onSurfaceVariant)
        if (frames.size > 1) Text("Call stack: " + frames.joinToString(" → ") { it.name }, style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant)
        // The running frame comes first so it is visible without scrolling; the frames that called it follow.
        frames.withIndex().reversed().forEach { (i, f) ->
            val innermost = i == frames.lastIndex
            Surface(color = if (innermost) c.surfaceVariant else c.surface, shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (innermost && frames.size > 1) "${f.name} (running now)" else if (frames.size > 1) "${f.name} (waiting)" else f.name,
                        style = MaterialTheme.typography.labelLarge)
                    if (f.vars.isEmpty()) Text("No variables yet.", color = c.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    f.vars.forEach { (name, v) ->
                        val isChanged = innermost && name in changed
                        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            Text(name, fontFamily = CodeFont, fontWeight = FontWeight.SemiBold)
                            Text(" = ", fontFamily = CodeFont)
                            Text(v.r, fontFamily = CodeFont, softWrap = true, modifier = Modifier.weight(1f, fill = false),
                                fontWeight = if (isChanged) FontWeight.Bold else FontWeight.Normal,
                                color = if (isChanged) c.primary else c.onSurface)
                            Text("  ${v.t}${if (isChanged) " · changed" else ""}", style = MaterialTheme.typography.bodyMedium,
                                color = c.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        returning?.let { Text(it, fontFamily = CodeFont, color = c.primary) }
    }
}

@Composable
private fun OutputPanel(text: String) {
    val c = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Output", style = MaterialTheme.typography.titleMedium)
        Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text(text.ifEmpty { "Nothing printed yet." }, fontFamily = CodeFont, modifier = Modifier.padding(12.dp),
                color = if (text.isEmpty()) c.onSurfaceVariant else c.onSurface)
        }
    }
}
