package com.encatch.androidtester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.encatch.android.EncatchInlineFormView
import com.encatch.core.Encatch

@Composable
fun SetupScreen(
    initialEnvironment: TesterEnvironment,
    onContinue: (environment: TesterEnvironment, apiKey: String, formId: String, interceptorFormId: String) -> Unit,
) {
    var environment by remember { mutableStateOf(initialEnvironment) }
    var apiKey by remember { mutableStateOf("") }
    var formId by remember { mutableStateOf("") }
    var interceptorFormId by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Encatch Tester — Setup", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Enter your own API key and default form id. Saved locally on this device — this same APK works for any tester or environment.")
        Spacer(Modifier.height(16.dp))

        Text("Environment", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TesterEnvironment.entries.forEachIndexed { index, env ->
                SegmentedButton(
                    selected = environment == env,
                    onClick = { environment = env },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TesterEnvironment.entries.size),
                ) { Text(env.label) }
            }
        }
        Text(
            "${environment.apiBaseUrl} · ${environment.webHost}",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(16.dp))
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key *") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(formId, { formId = it }, label = { Text("Default form id (feedback config) *") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            interceptorFormId,
            { interceptorFormId = it },
            label = { Text("Interceptor test form id (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onContinue(environment, apiKey.trim(), formId.trim(), interceptorFormId.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        ) { Text("Save & continue") }
    }
}

@Composable
fun LoginScreen(
    savedUsers: List<TestUser>,
    onSelectUser: (TestUser) -> Unit,
    onSaveNewUser: (TestUser) -> Unit,
    onEditProfile: (String) -> Unit,
    selectedUsername: String?,
    onIdentify: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    var showNewUserForm by remember { mutableStateOf(false) }
    var newUsername by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var newDisplayName by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Log in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Mock login — calls Encatch.identifyUser(username). Saved users are local to this tester, independent of the SDK.")
        Spacer(Modifier.height(16.dp))

        Text("Saved users", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        if (savedUsers.isEmpty()) {
            Text("No saved users yet.", style = MaterialTheme.typography.bodySmall)
        }
        savedUsers.forEach { user ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { onSelectUser(user) },
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(user.username, style = MaterialTheme.typography.titleSmall)
                    if (user.displayName.isNotBlank() || user.email.isNotBlank()) {
                        Text(listOf(user.displayName, user.email).filter { it.isNotBlank() }.joinToString(" · "))
                    }
                    if (selectedUsername == user.username) {
                        Text("Selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        if (!showNewUserForm) {
            TextButton(onClick = { showNewUserForm = true }) { Text("+ New user") }
        } else {
            OutlinedTextField(newUsername, { newUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(newDisplayName, { newDisplayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSaveNewUser(TestUser(newUsername.trim(), newEmail.trim(), newDisplayName.trim()))
                        showNewUserForm = false
                        newUsername = ""; newEmail = ""; newDisplayName = ""
                    },
                    enabled = newUsername.isNotBlank(),
                ) { Text("Save user") }
                TextButton(onClick = { showNewUserForm = false }) { Text("Cancel") }
            }
        }

        if (selectedUsername != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { onEditProfile(selectedUsername) }) { Text("Edit profile before sign in") }
        }

        Spacer(Modifier.height(24.dp))
        Button(onClick = onIdentify, enabled = selectedUsername != null) { Text("Identify user") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onChangeSetup) { Text("Change API key & setup") }
    }
}

@Composable
fun EditProfileScreen(
    username: String,
    initialEmail: String,
    initialDisplayName: String,
    onSave: (email: String, displayName: String) -> Unit,
    onBack: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var displayName by remember { mutableStateOf(initialDisplayName) }

    Column(Modifier.padding(24.dp)) {
        Text("Edit profile", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Username: $username", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onSave(email.trim(), displayName.trim()) }) { Text("Save & identify") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun HomeScreen(
    userName: String?,
    lastEvent: String,
    interceptorFormId: String?,
    onShowModalForm: () -> Unit,
    onShowPrefilledForm: () -> Unit,
    onShowInterceptorForm: (String) -> Unit,
    onEditProfile: () -> Unit,
) {
    LaunchedEffect(Unit) {
        Encatch.trackScreen("Home")
        Encatch.trackEvent("home_viewed")
    }

    Column(Modifier.padding(24.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineSmall)
        if (!userName.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signed in as $userName")
                TextButton(onClick = onEditProfile) { Text("Edit profile") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Last event: $lastEvent")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onShowModalForm) { Text("Show Form") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShowPrefilledForm) { Text("Show Form (prefilled)") }
        if (!interceptorFormId.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onShowInterceptorForm(interceptorFormId) }) { Text("Show Form (interceptor test)") }
        }
    }
}

private val TRACK_EVENT_PRESETS = listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed")
private val TRACK_SCREEN_PRESETS = listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test")

@Composable
fun EventsScreen(onTrackEvent: (String) -> Unit, onTrackScreen: (String) -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }
    var customEvent by remember { mutableStateOf("test_event") }
    var customScreen by remember { mutableStateOf("/dashboard/encatch-test") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Events", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))

        Text("trackEvent presets", style = MaterialTheme.typography.labelLarge)
        TRACK_EVENT_PRESETS.forEach { name ->
            Button(onClick = { onTrackEvent(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(name)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(customEvent, { customEvent = it }, label = { Text("Custom event") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onTrackEvent(customEvent.trim()) }, enabled = customEvent.isNotBlank()) { Text("Fire") }

        Spacer(Modifier.height(24.dp))
        Text("trackScreen presets", style = MaterialTheme.typography.labelLarge)
        TRACK_SCREEN_PRESETS.forEach { path ->
            Button(onClick = { onTrackScreen(path) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(path)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(customScreen, { customScreen = it }, label = { Text("Custom screen") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onTrackScreen(customScreen.trim()) }, enabled = customScreen.isNotBlank()) { Text("Track") }
    }
}

@Composable
fun InlineExactScreen(exactFormId: String, onShowExact: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineExact") }
    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Inline (Exact)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Claims \"$exactFormId\" — only renders inline when that exact form id is shown.")
        Spacer(Modifier.height(8.dp))
        Button(onClick = onShowExact) { Text("Show Exact Form (renders inline below)") }
        Spacer(Modifier.height(16.dp))
        // Only one tab's screen is composed at a time in this app (a plain when-based nav, not an
        // IndexedStack), so there's no risk of an offstage tab's inline slot stealing registration —
        // unlike Flutter's tester, no enabled-gating is needed here.
        AndroidView(
            factory = { context -> EncatchInlineFormView(context).apply { formId = exactFormId } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun InlineAnyScreen(onShowWildcard: (String) -> Unit, onTriggerFallback: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineAny") }
    var wildcardFormId by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Inline (Any)", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Catches any form id not exactly claimed elsewhere.")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(wildcardFormId, { wildcardFormId = it }, label = { Text("Form id") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onShowWildcard(wildcardFormId.trim()) }, enabled = wildcardFormId.isNotBlank()) {
            Text("Show Form (renders inline below)")
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.OutlinedButton(onClick = onTriggerFallback) {
            Text("Trigger unmatched form → modal fallback")
        }
        Spacer(Modifier.height(16.dp))
        AndroidView(
            factory = { context -> EncatchInlineFormView(context).apply { formId = null } },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun SettingsScreen(
    prefs: TesterPrefs,
    onSetLocale: () -> Unit,
    onSetCountry: () -> Unit,
    onChangeSetup: () -> Unit,
) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }

    Column(Modifier.padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Environment: ${prefs.environment.label}")
        Text("Form id: ${prefs.formId}")
        Text("API base URL: ${prefs.apiBaseUrl ?: "(default)"}")
        Text("Web host: ${prefs.webHost ?: "(default)"}")
        Text("Interceptor form id: ${prefs.interceptorFormId ?: "(none)"}")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSetLocale) { Text("Set Locale → fr-FR") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onSetCountry) { Text("Set Country → FR") }
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onChangeSetup) { Text("Change API key & setup") }
    }
}

@Composable
fun BillingScreen(route: String, onBackToHome: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Billing") }

    Column(Modifier.padding(24.dp)) {
        Text("Billing", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Reached via CTA app_navigate route: \"$route\"")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBackToHome) { Text("Back to home") }
    }
}

@Composable
fun RouteNotFoundScreen(route: String, onGoBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("RouteNotFound") }

    Column(Modifier.padding(24.dp)) {
        Text("Route not found", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("The CTA requested an unmapped route: \"$route\"")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoBack) { Text("Go back") }
    }
}
