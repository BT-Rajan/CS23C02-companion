package app.cs23c02.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import app.cs23c02.companion.AppInfo
import app.cs23c02.companion.core.Validation
import app.cs23c02.companion.ui.AppViewModel
import app.cs23c02.companion.ui.UiState

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize().semantics { contentDescription = "Loading" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
fun OfflineScreen(vm: AppViewModel) = FormPage(
    title = "You're offline",
    lead = "We couldn't reach the server to check your account. Connect to the internet and try again.",
) {
    PrimaryButton("Try again", busy = false) { vm.start() }
}

@Composable
fun WelcomeScreen(vm: AppViewModel) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(AppInfo.NAME, style = MaterialTheme.typography.headlineMedium)
            Text(
                "Read each lesson, watch Python run line by line, then practise. Use the activation code printed in your book.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            PrimaryButton("Create account", busy = false) { vm.openCreateAccount() }
            OutlinedButton(onClick = { vm.openSignIn() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign in") }
            if (vm.previewTutor && vm.demos.isNotEmpty()) {
                QuietButton("Try the tutor (test build only)") { vm.openTutor() }
            }
        }
    }
}

@Composable
fun CreateAccountScreen(vm: AppViewModel, s: UiState) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    FormPage("Create account", lead = "We'll email you a code to confirm your address. You need it to recover your account.", error = s.error, info = s.info) {
        Field("Email", email, { email = it }, s.fieldErrors["email"], keyboard = KeyboardType.Email)
        Field("Password", password, { password = it }, s.fieldErrors["password"], keyboard = KeyboardType.Password,
            ime = ImeAction.Done, onDone = { vm.createAccount(email, password) }, password = true, supporting = "At least 10 characters.")
        PrimaryButton("Continue", s.busy) { vm.createAccount(email, password) }
        QuietButton("I already have an account", enabled = !s.busy) { vm.openSignIn() }
    }
}

@Composable
fun VerifyEmailScreen(vm: AppViewModel, s: UiState, email: String) {
    var code by rememberSaveable { mutableStateOf("") }
    FormPage("Check your email", lead = "Enter the 6-digit code we sent to $email.", error = s.error, info = s.info) {
        Field("6-digit code", code, { code = Validation.cleanSixDigits(it) }, s.fieldErrors["code"], keyboard = KeyboardType.NumberPassword,
            ime = ImeAction.Done, onDone = { vm.verify(email, code) })
        PrimaryButton("Verify", s.busy) { vm.verify(email, code) }
        QuietButton("Send a new code", enabled = !s.busy) { vm.resendCode(email) }
    }
}

@Composable
fun SignInScreen(vm: AppViewModel, s: UiState) {
    var email by rememberSaveable { mutableStateOf(s.lastEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    FormPage("Sign in", error = s.error, info = s.info) {
        Field("Email", email, { email = it }, s.fieldErrors["email"], keyboard = KeyboardType.Email)
        Field("Password", password, { password = it }, s.fieldErrors["password"], keyboard = KeyboardType.Password,
            ime = ImeAction.Done, onDone = { vm.signIn(email, password) }, password = true)
        PrimaryButton("Sign in", s.busy) { vm.signIn(email, password) }
        QuietButton("Forgot password?", enabled = !s.busy) { vm.openForgot(email) }
    }
}

@Composable
fun ForgotScreen(vm: AppViewModel, s: UiState, initialEmail: String) {
    var email by rememberSaveable { mutableStateOf(initialEmail) }
    FormPage("Reset your password", lead = "We'll email you a 6-digit code.", error = s.error, info = s.info) {
        Field("Email", email, { email = it }, s.fieldErrors["email"], keyboard = KeyboardType.Email, ime = ImeAction.Done,
            onDone = { vm.sendResetCode(email) })
        PrimaryButton("Send code", s.busy) { vm.sendResetCode(email) }
    }
}

@Composable
fun ResetScreen(vm: AppViewModel, s: UiState, email: String) {
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    FormPage("Choose a new password", lead = "Enter the code sent to $email. Every device will be signed out.", error = s.error, info = s.info) {
        Field("6-digit code", code, { code = Validation.cleanSixDigits(it) }, s.fieldErrors["code"], keyboard = KeyboardType.NumberPassword)
        Field("New password", password, { password = it }, s.fieldErrors["password"], keyboard = KeyboardType.Password,
            ime = ImeAction.Done, onDone = { vm.resetPassword(email, code, password) }, password = true, supporting = "At least 10 characters.")
        PrimaryButton("Change password", s.busy) { vm.resetPassword(email, code, password) }
        QuietButton("Send a new code", enabled = !s.busy) { vm.sendResetCode(email) }
    }
}
