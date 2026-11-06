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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.cs23c02.companion.api.AssessmentQuestion
import app.cs23c02.companion.api.AttemptView
import app.cs23c02.companion.api.ProgressSummary
import app.cs23c02.companion.api.ReviewItem
import app.cs23c02.companion.content.InstalledPackage
import app.cs23c02.companion.ui.AssessmentUi
import app.cs23c02.companion.ui.MeUi
import app.cs23c02.companion.ui.ReaderUi
import app.cs23c02.companion.ui.theme.CodeFont
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm")
internal fun whenText(iso: String?): String =
    runCatching { WHEN.format(Instant.parse(iso!!).atZone(ZoneId.systemDefault())) }.getOrDefault("")

internal fun timeLeft(expiresAt: String?, now: Instant): String? {
    val end = runCatching { Instant.parse(expiresAt!!) }.getOrNull() ?: return null
    val m = Duration.between(now, end).toMinutes()
    return when { m <= 0 -> "Time is up: submit to see your result"; m < 60 -> "$m min left"; else -> "${m / 60} h ${m % 60} min left" }
}

/** Screen 12 (A7): intro with history → one question per page with autosave → submit → result and review. */
@Composable
fun AssessmentScreen(
    title: String, questionCount: Int, ui: AssessmentUi,
    onStart: () -> Unit, onChoose: (String, String) -> Unit, onGoto: (Int) -> Unit, onSubmit: () -> Unit,
    onReview: (String) -> Unit, onBackToIntro: () -> Unit, onClose: () -> Unit, now: () -> Instant = Instant::now,
) {
    val a = ui.attempt
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Unit assessment", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(a?.title?.ifEmpty { null } ?: title, style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            when {
                a == null -> Intro(questionCount, ui, onStart, onReview)
                !a.submitted -> Answering(a, ui, onChoose, onGoto, onSubmit, now)
                else -> Result(a, onBackToIntro)
            }
            ui.error?.let { Message(it, isError = true) }
        }
    }
}

@Composable
private fun Intro(n: Int, ui: AssessmentUi, onStart: () -> Unit, onReview: (String) -> Unit) {
    val h = ui.history
    val open = h?.attempts?.any { it.status == "in_progress" } == true
    Text("$n questions. Your answers are saved as you go.", style = MaterialTheme.typography.bodyLarge)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("You have 3 hours once you start; after that your saved answers are marked.",
            "You see your score, the right answers and explanations after you submit.",
            "You can take it again 24 hours after your last attempt.",
            "It needs a connection.").forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
    }
    if (ui.loading && h == null) CircularProgressIndicator(Modifier.size(24.dp))
    val wait = h?.nextAttemptAt
    if (wait != null && !open) Message("You can take it again from ${whenText(wait)}.", isError = false)
    PrimaryButton(if (open) "Continue" else "Start", busy = ui.loading && h != null, enabled = h != null && (open || wait == null)) { onStart() }
    val done = h?.attempts?.filter { it.status == "submitted" }.orEmpty()
    if (done.isNotEmpty()) {
        Text("Your attempts", style = MaterialTheme.typography.titleMedium)
        done.reversed().forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${whenText(s.submittedAt)}  ·  ${s.score} of ${s.maxScore}" + if (s.autoSubmitted) " (time ran out)" else "",
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onReview(s.attemptId) }) { Text("Review") }
            }
        }
    }
}

@Composable
private fun Answering(a: AttemptView, ui: AssessmentUi, onChoose: (String, String) -> Unit, onGoto: (Int) -> Unit, onSubmit: () -> Unit,
                      now: () -> Instant) {
    val c = MaterialTheme.colorScheme
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(a.attemptId) { while (true) { delay(30_000); tick++ } }
    var confirm by remember { mutableStateOf(false) }
    val qs = a.questions
    val i = ui.index.coerceIn(0, (qs.size - 1).coerceAtLeast(0))
    val q = qs.getOrNull(i) ?: return
    tick.let { timeLeft(a.expiresAt, now())?.let { t -> Text(t, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant) } }
    // Navigator: every question, its number and whether it is answered (in text, not colour alone).
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        qs.forEachIndexed { k, x ->
            val answered = ui.answers.containsKey(x.itemId)
            OutlinedButton(onClick = { onGoto(k) }, border = BorderStroke(if (k == i) 2.dp else 1.dp, if (k == i) c.primary else c.outline),
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "Question ${k + 1}, ${if (answered) "answered" else "not answered"}${if (k == i) ", current" else ""}"
                }) { Text("${k + 1}${if (answered) " ✓" else ""}") }
        }
    }
    Text("Question ${i + 1} of ${qs.size}", style = MaterialTheme.typography.labelLarge)
    QuestionBody(q)
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        q.options.forEach { o ->
            val picked = ui.answers[q.itemId] == o.id
            Surface(shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, if (picked) c.primary else c.outline),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    .selectable(selected = picked, role = Role.RadioButton) { onChoose(q.itemId, o.id) }) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = picked, onClick = null)
                    Text(o.text, style = MaterialTheme.typography.bodyLarge, fontFamily = if (q.type == "trace") CodeFont else null,
                        modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
    Text(if (ui.unsaved) "Not saved yet: it will be saved with your next answer or when you submit." else "Saved",
        style = MaterialTheme.typography.bodyMedium, color = if (ui.unsaved) c.error else c.onSurfaceVariant,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onGoto(i - 1) }, enabled = i > 0, modifier = Modifier.heightIn(min = 48.dp)) { Text("Previous") }
        if (i < qs.size - 1) OutlinedButton(onClick = { onGoto(i + 1) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Next") }
    }
    PrimaryButton("Submit", busy = ui.submitting) { confirm = true }
    if (confirm) {
        val missing = qs.count { !ui.answers.containsKey(it.itemId) }
        AlertDialog(onDismissRequest = { confirm = false },
            title = { Text("Submit your answers?") },
            text = { Text(if (missing == 0) "You have answered every question. You can't change answers after submitting."
                          else "$missing question${if (missing == 1) " is" else "s are"} not answered yet. You can't change answers after submitting.") },
            confirmButton = { TextButton(onClick = { confirm = false; onSubmit() }) { Text("Submit") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep working") } })
    }
}

@Composable
private fun QuestionBody(q: AssessmentQuestion) {
    Text(stemText(q.stem), style = MaterialTheme.typography.titleMedium)
    q.code?.let { CodeBox(it) }
}

@Composable
private fun CodeBox(code: String) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp).horizontalScroll(rememberScrollState())) {
            code.split('\n').forEachIndexed { k, line ->
                Row { Text("${k + 1}".padStart(2) + "  ", fontFamily = CodeFont, color = c.onSurfaceVariant); Text(line, fontFamily = CodeFont, softWrap = false) }
            }
        }
    }
}

@Composable
private fun Result(a: AttemptView, onBackToIntro: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.primaryContainer, contentColor = c.onPrimaryContainer, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Your score", style = MaterialTheme.typography.labelLarge)
            Text("${a.score} of ${a.maxScore}", style = MaterialTheme.typography.headlineMedium)
            Text("Submitted ${whenText(a.submittedAt)}" + if (a.autoSubmitted) ". Time ran out, so your saved answers were marked." else "",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("Review", style = MaterialTheme.typography.titleMedium)
    a.review.forEachIndexed { k, r -> ReviewCard(k + 1, r) }
    OutlinedButton(onClick = onBackToIntro, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to the assessment") }
}

@Composable
private fun ReviewCard(n: Int, r: ReviewItem) {
    val c = MaterialTheme.colorScheme
    Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$n. ${if (r.correct) "✓ Right" else if (r.chosen == null) "– Not answered" else "✗ Not right"}", style = MaterialTheme.typography.labelLarge)
            Text(stemText(r.stem), style = MaterialTheme.typography.bodyLarge)
            r.code?.let { CodeBox(it) }
            r.options.forEach { o ->
                val mark = when { o.id == r.answer -> "✓ "; o.id == r.chosen -> "✗ "; else -> "   " }
                val tag = when { o.id == r.answer && o.id == r.chosen -> "  (your answer)"; o.id == r.answer -> "  (right answer)"
                    o.id == r.chosen -> "  (your answer)"; else -> "" }
                Text(mark + o.text + tag, style = MaterialTheme.typography.bodyMedium,
                    fontFamily = if (r.type == "trace") CodeFont else null)
            }
            r.why?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (r.explanation.isNotBlank()) Text(r.explanation, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Screen 13: progress by unit, exercises, assessment results, and the account. Nothing here is a placeholder. */
@Composable
fun MeScreen(email: String, pkg: InstalledPackage?, r: ReaderUi, me: MeUi, sample: Boolean, appVersion: String,
             onAssessment: (String, String) -> Unit, onSignOut: () -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val s: ProgressSummary? = me.summary
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Me", style = MaterialTheme.typography.titleLarge)
                    Text(email, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            if (me.offline) Text("Offline: showing what this phone knows.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
            pkg?.manifest?.nav?.units?.forEachIndexed { i, u ->
                val ids = u.topics.flatMap { it.lessons }
                val done = ids.count { it in r.completed || it in s?.completed.orEmpty() }
                val best = u.assessment?.let { a -> s?.assessments?.firstOrNull { it.assessmentId == a } }
                Surface(color = c.surfaceVariant, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Unit ${i + 1} · ${u.title}", style = MaterialTheme.typography.labelLarge)
                        Text("Lessons: $done of ${ids.size} done", style = MaterialTheme.typography.bodyLarge)
                        u.assessment?.let { a ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (best == null) "Unit assessment: not taken yet"
                                     else "Unit assessment: best ${best.bestScore} of ${best.maxScore} (${best.attempts} attempt${if (best.attempts == 1) "" else "s"})",
                                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { onAssessment(a, u.id) }) { Text("Open") }
                            }
                        }
                    }
                }
            }
            s?.exercisesPassed?.let { Text("Exercises passed: ${it.size}", style = MaterialTheme.typography.bodyLarge) }
            OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (sample) "Leave the sample lessons" else "Sign out")
            }
            Text("App version $appVersion", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        }
    }
}
