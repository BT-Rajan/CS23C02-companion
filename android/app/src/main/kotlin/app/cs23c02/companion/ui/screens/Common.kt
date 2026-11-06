package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** One layout for every form screen: title, optional lead text, fields, messages, one primary action, quiet extras. */
@Composable
fun FormPage(
    title: String,
    lead: String? = null,
    error: String? = null,
    info: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (lead != null) Text(lead, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (info != null) Message(info, isError = false)
            content()
            if (error != null) Message(error, isError = true)
        }
    }
}

@Composable
fun Message(text: String, isError: Boolean) {
    val c = MaterialTheme.colorScheme
    Surface(
        color = if (isError) c.errorContainer else c.primaryContainer,
        contentColor = if (isError) c.onErrorContainer else c.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        // The "Problem:" prefix means meaning never depends on colour alone.
        Text(if (isError) "Problem: $text" else text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PrimaryButton(label: String, busy: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text(label)
    }
}

@Composable
fun QuietButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(label) }
}

@Composable
fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
    password: Boolean = false,
    supporting: String? = null,
) {
    var shown by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = error != null,
        supportingText = (error ?: supporting)?.let { { Text(it) } },
        visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (password) { { TextButton(onClick = { shown = !shown }) { Text(if (shown) "Hide" else "Show") } } } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
        modifier = Modifier.fillMaxWidth(),
    )
}
