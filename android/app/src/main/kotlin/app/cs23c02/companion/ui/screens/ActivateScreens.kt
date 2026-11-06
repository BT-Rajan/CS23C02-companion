package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.sp
import app.cs23c02.companion.core.ActivationCode
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.UiState
import app.cs23c02.companion.ui.theme.CodeFont
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp

private fun Modifier.heightIn48() = heightIn(min = 48.dp)

/** Screen 5. Accepts paste and any spacing/case; groups as XXXX-XXXX-XXXX-XXXX; flags a typo the moment 16 characters are in. */
@Composable
fun ActivateScreen(vm: AppViewModel, s: UiState) {
    var raw by rememberSaveable { mutableStateOf("") }
    val clean = ActivationCode.clean(raw)
    val shown = ActivationCode.pretty(clean)
    val typo = clean.length == ActivationCode.LENGTH && !ActivationCode.checksumOk(clean)
    val fieldError = s.fieldErrors["code"] ?: if (typo) "That code doesn't look right. Check each character." else null
    FormPage(
        "Activate your book",
        lead = "Find the 16-character code inside the back cover. Letters O, I and L are read as 0, 1 and 1.",
        error = s.error, info = s.info,
    ) {
        OutlinedTextField(
            value = TextFieldValue(shown, selection = TextRange(shown.length)),
            onValueChange = { raw = ActivationCode.clean(it.text) },
            label = { Text("Activation code") },
            placeholder = { Text("XXXX-XXXX-XXXX-XXXX") },
            singleLine = true,
            isError = fieldError != null,
            supportingText = { Text(fieldError ?: "${clean.length} of 16 characters") },
            textStyle = TextStyle(fontFamily = CodeFont, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.activate(clean) }),
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryButton("Activate", s.busy) { vm.activate(clean) }
        QuietButton("Sign out", enabled = !s.busy) { vm.signOut() }
    }
}

/**
 * Screen 6, A1 version. It only confirms the account and activation. The real Home (Continue card, units) is roadmap A2,
 * and the text says so plainly rather than pretending lessons exist.
 */
@Composable
fun HomeScreen(vm: AppViewModel, s: UiState, me: app.cs23c02.companion.api.Me) {
    FormPage("You're all set", lead = "Signed in as ${me.email}.", error = s.error, info = s.info) {
        me.entitlements.forEach { Message("Activated: ${it.title}", isError = false) }
        if (vm.demos.isNotEmpty()) {
            Text("Watch Python run line by line", style = MaterialTheme.typography.titleMedium)
            Text("Demo programs (not book lessons). Step through each one and watch the variables change.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            vm.demos.forEach { d ->
                androidx.compose.material3.OutlinedButton(onClick = { vm.openStepper(d.id) },
                    modifier = Modifier.fillMaxWidth().heightIn48()) { Text(d.title) }
            }
        }
        Text("Lessons arrive in the next update of this test build.", style = MaterialTheme.typography.bodyLarge)
        QuietButton("Sign out", enabled = !s.busy) { vm.signOut() }
    }
}
