package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cs23c02.companion.stepper.StepProgram
import app.cs23c02.companion.tutor.Focus
import app.cs23c02.companion.tutor.GuidedTyping
import app.cs23c02.companion.tutor.ProgramSearch
import app.cs23c02.companion.tutor.Verdict
import app.cs23c02.companion.ui.theme.CodeFont

/** ADR 0014 §1: "Which program do you want?" — the student's own words, matched against the book's programs. */
@Composable
fun TutorScreen(programs: List<StepProgram>, onShowMe: (String) -> Unit, onTypeIt: (String) -> Unit, onClose: () -> Unit,
                onOwnCode: (() -> Unit)? = null) {
    var query by rememberSaveable { mutableStateOf("") }
    val results = ProgramSearch.search(query, programs)
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ask for a program", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Close") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Which program do you want?") },
                placeholder = { Text("e.g. find the factorial of a number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (results.isEmpty()) {
                Text("No book program matches \"${query.trim()}\" yet. Try other words, or pick one below.",
                    style = MaterialTheme.typography.bodyLarge)
            }
            (results.ifEmpty { programs }).forEach { p -> ProgramCard(p, onShowMe, onTypeIt) }
            if (onOwnCode != null) {
                Text("Or write your own program", style = MaterialTheme.typography.titleMedium)
                Text("Type any Python. The tutor explains each line, points to the book, and lets you step through it.",
                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = onOwnCode, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Write your own code") }
            }
        }
    }
}

@Composable
private fun ProgramCard(p: StepProgram, onShowMe: (String) -> Unit, onTypeIt: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            p.mainRef()?.let { BookRefRow(it) }
            Text("${p.lines.size} lines", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onShowMe(p.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Show me") }
                OutlinedButton(onClick = { onTypeIt(p.id) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("I'll type it") }
            }
        }
    }
}

/** The program's main topic: the most advanced construct it uses (latest unit/section in the book). */
private fun StepProgram.mainRef() = refs.values.maxByOrNull { r ->
    r.section.split('.').mapNotNull { it.toIntOrNull() }.let { (it.getOrElse(0) { 0 }) * 100 + it.getOrElse(1) { 0 } }
}

internal val SYMBOLS = listOf(":", "(", ")", "[", "]", "\"", "=", "+", "#")

/**
 * ADR 0014 §3: the student types the book's program; the tutor panel follows every keystroke.
 * Enter keeps the indentation of the line above and adds 4 spaces after a line ending with ':'.
 */
@Composable
fun TypeItScreen(program: StepProgram, onStepThrough: () -> Unit, onClose: () -> Unit, initialText: String = "",
                 onRunMine: ((String) -> Unit)? = null, busy: Boolean = false) {
    val tutor = remember(program.id) { GuidedTyping(program) }
    var text by remember(program.id) { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var hintFor by rememberSaveable { mutableIntStateOf(-1) }
    var hintLevel by rememberSaveable { mutableIntStateOf(0) }
    val g = tutor.check(text.text)

    fun insert(s: String) {
        val t = text.text
        val sel = text.selection
        val nt = t.substring(0, sel.start) + s + t.substring(sel.end)
        text = TextFieldValue(nt, TextRange(sel.start + s.length))
    }

    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(program.title, style = MaterialTheme.typography.titleLarge)
                    Text("${g.linesDone} of ${g.linesTotal} lines right", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TutorPanel(program, tutor, g.focus, hintFor, hintLevel,
                    onHint = { line -> if (hintFor != line) { hintFor = line; hintLevel = 1 } else hintLevel = (hintLevel + 1).coerceAtMost(2) },
                    onTypeForMe = { line ->
                        val t = text.text
                        val prefix = if (t.isEmpty() || t.endsWith("\n")) "" else "\n"
                        insert(prefix + tutor.targetText(line) + "\n")
                        hintFor = -1; hintLevel = 0
                    },
                    onStepThrough = onStepThrough)
                Editor(text, g.checks.associate { it.editorLine to it.verdict }) { nv -> text = autoIndent(text, nv) }
                if (onRunMine != null && text.text.isNotBlank() && g.focus != Focus.Done) {
                    OutlinedButton(onClick = { onRunMine(text.text) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(if (busy) "Running…" else "Step through my code as it is")
                    }
                }
            }
            Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { insert("    ") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tab") }
                    SYMBOLS.forEach { sym ->
                        TextButton(onClick = { insert(sym) }, modifier = Modifier.heightIn(min = 48.dp)
                            .semantics { contentDescription = "Insert $sym" }) { Text(sym, fontFamily = CodeFont, fontSize = 18.sp) }
                    }
                }
            }
        }
    }
}

/** Enter keeps the previous line's indentation, plus 4 spaces after ':'. Only acts on a single typed newline. */
internal fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    val o = old.text
    val n = new.text
    val at = new.selection.start
    if (n.length != o.length + 1 || at == 0 || n[at - 1] != '\n' || n.removeRange(at - 1, at) != o) return new
    val lineStart = n.lastIndexOf('\n', at - 2) + 1
    val prev = n.substring(lineStart, at - 1)
    var indent = prev.length - prev.trimStart().length
    if (prev.trimEnd().endsWith(":")) indent += 4
    if (indent == 0) return new
    val pad = " ".repeat(indent)
    return TextFieldValue(n.substring(0, at) + pad + n.substring(at), TextRange(at + indent))
}

@Composable
private fun TutorPanel(
    program: StepProgram, tutor: GuidedTyping, focus: Focus, hintFor: Int, hintLevel: Int,
    onHint: (Int) -> Unit, onTypeForMe: (Int) -> Unit, onStepThrough: () -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val (bg, fg) = when (focus) {
        is Focus.Fix -> c.errorContainer to c.onErrorContainer
        Focus.Done -> c.primaryContainer to c.onPrimaryContainer
        else -> c.secondary.copy(alpha = 0.12f) to c.onSurface
    }
    Surface(color = bg, contentColor = fg, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (focus) {
                is Focus.Next, is Focus.Typing -> {
                    val (info, line) = if (focus is Focus.Next) focus.target to focus.targetLine else (focus as Focus.Typing).target to focus.targetLine
                    Text(if (focus is Focus.Typing) "Keep going: line $line" else "Next: line $line",
                        style = MaterialTheme.typography.labelLarge)
                    info?.let { Text(it.explain, style = MaterialTheme.typography.bodyLarge) }
                    program.stepNotes[line.toString()]?.let { Text("Note: $it", style = MaterialTheme.typography.bodyLarge) }
                    program.refsFor(line).forEach { BookRefRow(it) }
                    if (hintFor == line && hintLevel >= 1) Text("Shape:  ${info?.shape?.trim().orEmpty()}", fontFamily = CodeFont)
                    if (hintFor == line && hintLevel >= 2) Text("The book's line:  ${tutor.targetText(line).trim()}", fontFamily = CodeFont)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!(hintFor == line && hintLevel >= 2)) {
                            OutlinedButton(onClick = { onHint(line) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (hintFor == line && hintLevel == 1) "Show the line" else "Hint")
                            }
                        } else {
                            OutlinedButton(onClick = { onTypeForMe(line) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Type it for me") }
                        }
                    }
                }
                is Focus.Fix -> {
                    val label = when (focus.check.verdict) {
                        Verdict.INDENT -> "Check the indentation on line ${focus.check.editorLine}"
                        Verdict.TYPO -> "Almost right: line ${focus.check.editorLine}"
                        else -> "Line ${focus.check.editorLine} is different from the book"
                    }
                    Text("Problem: $label", style = MaterialTheme.typography.labelLarge)
                    focus.check.message?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    focus.target?.let { Text("This line should: ${it.explain}", style = MaterialTheme.typography.bodyMedium) }
                    program.refsFor(focus.check.targetLine).forEach { BookRefRow(it) }
                }
                is Focus.Extra -> {
                    Text("The book's program ends before line ${focus.editorLine}.", style = MaterialTheme.typography.labelLarge)
                    Text("Remove the extra lines, or keep them to experiment.", style = MaterialTheme.typography.bodyLarge)
                }
                Focus.Done -> {
                    Text("Well done: your program matches the book.", style = MaterialTheme.typography.labelLarge)
                    Text("Now watch it run, one line at a time.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onStepThrough, modifier = Modifier.heightIn(min = 48.dp)) { Text("Step through it") }
                }
            }
        }
    }
}

/** Monospace editor with a gutter: line number plus ✓ (right), ! (needs attention). Marks are text, not colour alone. */
@Composable
internal fun Editor(value: TextFieldValue, marks: Map<Int, Verdict>, onChange: (TextFieldValue) -> Unit) {
    val c = MaterialTheme.colorScheme
    val style = TextStyle(fontFamily = CodeFont, fontSize = 16.sp, lineHeight = 24.sp, color = c.onSurface)
    val count = value.text.count { it == '\n' } + 1
    val rowHeight = with(androidx.compose.ui.platform.LocalDensity.current) { style.lineHeight.toDp() }
    Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 8.dp)) {
            Column(Modifier.padding(start = 8.dp).width(44.dp)) {
                for (n in 1..count) {
                    val mark = when (marks[n]) {
                        Verdict.CORRECT, Verdict.EQUIVALENT -> "✓"
                        null -> " "
                        else -> "!"
                    }
                    Text("${n.toString().padStart(2)} $mark", maxLines = 1,
                        style = style.copy(color = if (mark == "!") c.error else c.onSurfaceVariant),
                        modifier = Modifier.height(rowHeight))
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState()).padding(end = 8.dp)) {
                BasicTextField(
                    value = value, onValueChange = onChange, textStyle = style, cursorBrush = SolidColor(c.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.widthIn(min = 260.dp).heightIn(min = 160.dp).background(c.surfaceVariant)
                        .semantics { contentDescription = "Code editor" },
                )
            }
        }
    }
}
