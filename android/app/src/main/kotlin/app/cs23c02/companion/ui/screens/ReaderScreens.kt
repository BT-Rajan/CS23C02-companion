package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.cs23c02.companion.api.Me
import app.cs23c02.companion.content.Block
import app.cs23c02.companion.content.InstalledPackage
import app.cs23c02.companion.content.Lesson
import app.cs23c02.companion.content.place
import app.cs23c02.companion.ui.ReaderUi
import app.cs23c02.companion.ui.theme.CodeFont
import kotlinx.coroutines.flow.debounce

/** Screen 6 (A2): one primary Continue action, then the units with completion, then the tutor. No tab bar. */
@Composable
fun HomeScreen(
    me: Me, sample: Boolean, r: ReaderUi, info: String?,
    onContinue: (String) -> Unit, onUnit: (String) -> Unit, onRetry: () -> Unit, onTutor: (() -> Unit)?, onSignOut: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.pkg?.manifest?.book?.title ?: "Your lessons", style = MaterialTheme.typography.titleLarge)
                    Text(me.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onSignOut) { Text(if (sample) "Leave" else "Sign out") }
            }
            if (sample) Message("Sample lessons written to try the app. They are not book content.", isError = false)
            if (r.offline && r.pkg != null) Text("Offline: showing the lessons saved on this phone.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info?.let { Message(it, isError = false) }
            val pkg = r.pkg
            when {
                pkg == null && r.syncing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(); Text("Getting your lessons…", style = MaterialTheme.typography.bodyLarge)
                }
                pkg == null -> {
                    Message(r.problem ?: "Lessons are not available yet.", isError = r.problem != null)
                    OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
                else -> {
                    r.problem?.let { Message(it, isError = true) }
                    ContinueCard(pkg, r, onContinue)
                    Text("Units", style = MaterialTheme.typography.titleMedium)
                    pkg.manifest.nav.units.forEachIndexed { i, u ->
                        val ids = u.topics.flatMap { it.lessons }
                        val done = ids.count { it in r.completed }
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onUnit(u.id) }
                            .semantics(mergeDescendants = true) { contentDescription = "${u.title}, $done of ${ids.size} lessons done" }
                            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Unit ${i + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(u.title, style = MaterialTheme.typography.bodyLarge)
                            }
                            Text(if (done == ids.size && ids.isNotEmpty()) "✓ Done" else "$done of ${ids.size}",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (onTutor != null) {
                Text("Learn a program line by line", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onTutor, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Ask for a program") }
            }
        }
    }
}

@Composable
private fun ContinueCard(pkg: InstalledPackage, r: ReaderUi, onContinue: (String) -> Unit) {
    val c = MaterialTheme.colorScheme
    val target = r.resume
    Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (target == null) {
                Text("You've finished every lesson.", style = MaterialTheme.typography.titleMedium)
                Text("Pick any unit below to revise.", style = MaterialTheme.typography.bodyLarge)
            } else {
                val place = pkg.manifest.nav.place(target)
                val started = r.lastOpened == target || r.completed.isNotEmpty()
                Text(if (started) "Continue" else "Start here", style = MaterialTheme.typography.labelLarge)
                place?.let { Text("Unit ${it.unitIndex + 1} · ${it.topic.title}", style = MaterialTheme.typography.bodyMedium) }
                Text(pkg.title(target), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { onContinue(target) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (started) "Continue" else "Start")
                }
            }
        }
    }
}

/** Screen 7: the unit's topics and lessons, each with its state in words (not colour alone). */
@Composable
fun UnitScreen(pkg: InstalledPackage, unitId: String, r: ReaderUi, onLesson: (String) -> Unit, onClose: () -> Unit) {
    val units = pkg.manifest.nav.units
    val index = units.indexOfFirst { it.id == unitId }
    val unit = units.getOrNull(index) ?: return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unit ${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(unit.title, style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onClose) { Text("Back") }
            }
            unit.topics.forEach { t ->
                Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                t.lessons.forEach { id ->
                    val state = when { id in r.completed -> "✓ Done"; id == r.lastOpened -> "▶ In progress"; else -> "" }
                    val minutes = runCatching { pkg.lesson(id)?.minutes }.getOrNull()
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { onLesson(id) }
                        .semantics(mergeDescendants = true) {}.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pkg.title(id), style = MaterialTheme.typography.bodyLarge)
                            minutes?.let { Text("$it min", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        Text(state, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

/**
 * Screen 8: the lesson as one vertical scroll with no menus (journey J3). Code examples can be stepped through line by line
 * or opened in the editor. The scroll position is saved, so Continue reopens exactly here.
 */
@Composable
fun LessonScreen(
    pkg: InstalledPackage, lessonId: String, done: Boolean, startAt: Int,
    onStep: ((title: String, code: String) -> Unit)?, onTryIt: ((String) -> Unit)?,
    onPosition: (Int) -> Unit, onDone: () -> Unit, onClose: () -> Unit, isLast: Boolean,
    onPractise: (() -> Unit)? = null,
    onExercise: ((String) -> Unit)? = null,
) {
    val lesson: Lesson? = runCatching { pkg.lesson(lessonId) }.getOrNull()
    val place = pkg.manifest.nav.place(lessonId)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startAt)
    LaunchedEffect(lessonId) {
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow { list.firstVisibleItemIndex }.debounce(500).collect { onPosition(it) }
    }
    DisposableEffect(lessonId) { onDispose { onPosition(list.firstVisibleItemIndex) } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(state = list, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        place?.let { Text("Unit ${it.unitIndex + 1} · ${it.topic.title}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(lesson?.title ?: "Lesson", style = MaterialTheme.typography.headlineMedium)
                        lesson?.minutes?.takeIf { it > 0 }?.let { Text("About $it min to read", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
            if (lesson == null) {
                item { Message("This lesson could not be opened. It may be damaged; it will be downloaded again next time you are online.", isError = true) }
            } else {
                lesson.blocks.forEach { b -> item { BlockView(b, lesson.title, onStep, onTryIt) } }
                val n = lesson.practice.items.size
                if (n > 0 && onPractise != null) item {
                    Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Check your understanding", style = MaterialTheme.typography.titleMedium)
                            Text("$n question${if (n == 1) "" else "s"}. You see straight away whether you are right, and why.",
                                style = MaterialTheme.typography.bodyLarge)
                            OutlinedButton(onClick = onPractise, modifier = Modifier.heightIn(min = 48.dp)) { Text("Practise") }
                        }
                    }
                }
                if (lesson.practice.exercises.isNotEmpty() && onExercise != null) item {
                    Surface(color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Write a program", style = MaterialTheme.typography.titleMedium)
                            lesson.practice.exercises.forEach { id ->
                                val title = runCatching { pkg.exercise(id)?.title }.getOrNull() ?: id
                                OutlinedButton(onClick = { onExercise(id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(title) }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (done) Text("✓ You have finished this lesson.", style = MaterialTheme.typography.bodyLarge)
                        PrimaryButton(if (isLast) "Done" else "Done · next lesson", busy = false, onClick = onDone)
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockView(b: Block, lessonTitle: String, onStep: ((String, String) -> Unit)?, onTryIt: ((String) -> Unit)?) {
    val c = MaterialTheme.colorScheme
    when (b.type) {
        "paragraph" -> Text(b.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
        "heading" -> Text(b.text.orEmpty(), style = MaterialTheme.typography.titleLarge)
        "definition" -> Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Keyword: ${b.term.orEmpty()}", style = MaterialTheme.typography.labelLarge)
                Text(b.text.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            }
        }
        "note" -> Surface(color = c.secondary.copy(alpha = 0.12f), shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Text("Note: ${b.text.orEmpty()}", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        "list" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.orEmpty().forEach { Text("•  $it", style = MaterialTheme.typography.bodyLarge) }
        }
        "syntax" -> Surface(color = c.surface, border = BorderStroke(1.dp, c.outline), shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Syntax", style = MaterialTheme.typography.labelLarge)
                Text(b.text.orEmpty(), fontFamily = CodeFont, modifier = Modifier.horizontalScroll(rememberScrollState()), softWrap = false)
            }
        }
        "code" -> CodeExample(b.code.orEmpty(), b.expectedOutput, lessonTitle, onStep, onTryIt)
        else -> Unit // a block type from a newer package: skipped, never a crash
    }
}

@Composable
private fun CodeExample(code: String, expected: String?, lessonTitle: String, onStep: ((String, String) -> Unit)?, onTryIt: ((String) -> Unit)?) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                code.split('\n').forEachIndexed { i, line ->
                    Row {
                        Text("${i + 1}".padStart(2), fontFamily = CodeFont, color = c.onSurfaceVariant, modifier = Modifier.widthIn(min = 28.dp))
                        Text(line, fontFamily = CodeFont, softWrap = false)
                    }
                }
            }
            expected?.takeIf { it.isNotEmpty() }?.let {
                Text("Output", style = MaterialTheme.typography.labelLarge)
                Text(it, fontFamily = CodeFont, fontWeight = FontWeight.Normal)
            }
            if (onStep != null || onTryIt != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    onStep?.let { s -> OutlinedButton(onClick = { s("Example: $lessonTitle", code) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Step through") } }
                    onTryIt?.let { t -> TextButton(onClick = { t(code) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try it yourself") } }
                }
            }
        }
    }
}
