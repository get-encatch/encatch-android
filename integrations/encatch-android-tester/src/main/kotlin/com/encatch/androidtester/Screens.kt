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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
    onContinue: (apiKey: String, formId: String, baseUrl: String, webHost: String, interceptorFormId: String) -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    var formId by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var webHost by remember { mutableStateOf("") }
    var interceptorFormId by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Encatch Tester — Setup", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Enter your own API key and default form id. Saved locally on this device — this same APK works for any tester or environment.")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key *") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(formId, { formId = it }, label = { Text("Default form id (feedback config) *") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(webHost, { webHost = it }, label = { Text("Web host (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            interceptorFormId,
            { interceptorFormId = it },
            label = { Text("Interceptor test form id (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { onContinue(apiKey.trim(), formId.trim(), baseUrl.trim(), webHost.trim(), interceptorFormId.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        ) { Text("Save & continue") }
    }
}

@Composable
fun LoginScreen(initialUserName: String, onLogIn: (String) -> Unit) {
    var userName by remember { mutableStateOf(initialUserName) }

    Column(Modifier.padding(24.dp)) {
        Text("Log in", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Mock login — calls Encatch.identifyUser(username).")
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(userName, { userName = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(onClick = { onLogIn(userName.trim()) }, enabled = userName.isNotBlank()) { Text("Log in") }
    }
}

@Composable
fun HomeScreen(
    lastEvent: String,
    interceptorFormId: String?,
    onShowModalForm: () -> Unit,
    onShowInterceptorForm: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
) {
    LaunchedEffect(Unit) {
        Encatch.trackScreen("Home")
        Encatch.trackEvent("home_viewed")
    }

    Column(Modifier.padding(24.dp)) {
        Text("Home", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("Last event: $lastEvent")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onShowModalForm) { Text("Show form (modal)") }
        if (!interceptorFormId.isNullOrBlank()) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onShowInterceptorForm(interceptorFormId) }) { Text("Interceptor test") }
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(Screen.Events) }) { Text("Events") }
            TextButton(onClick = { onNavigate(Screen.Inline) }) { Text("Inline") }
            TextButton(onClick = { onNavigate(Screen.Settings) }) { Text("Settings") }
        }
    }
}

@Composable
fun EventsScreen(onTrack: (String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }

    Column(Modifier.padding(24.dp)) {
        Text("Events", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed").forEach { name ->
            Button(onClick = { onTrack(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(name)
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun InlineScreen(
    exactFormId: String,
    onShowExact: () -> Unit,
    onShowWildcard: (String) -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Inline") }
    var wildcardFormId by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Inline forms", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(16.dp))
        Text("Exact (claims \"$exactFormId\")")
        AndroidView(
            factory = { context -> EncatchInlineFormView(context).apply { formId = exactFormId } },
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        Button(onClick = onShowExact) { Text("Show exact inline form") }

        Spacer(Modifier.height(24.dp))
        Text("Wildcard (catches any form id not exactly claimed elsewhere)")
        AndroidView(
            factory = { context -> EncatchInlineFormView(context).apply { formId = null } },
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )
        OutlinedTextField(wildcardFormId, { wildcardFormId = it }, label = { Text("Form id") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onShowWildcard(wildcardFormId.trim()) }, enabled = wildcardFormId.isNotBlank()) {
            Text("Show in wildcard slot")
        }

        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun SettingsScreen(prefs: TesterPrefs, onLogOut: () -> Unit, onClearSetup: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }

    Column(Modifier.padding(24.dp)) {
        Text("Settings", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text("Form id: ${prefs.formId}")
        Text("API base URL: ${prefs.apiBaseUrl ?: "(default)"}")
        Text("Web host: ${prefs.webHost ?: "(default)"}")
        Text("Interceptor form id: ${prefs.interceptorFormId ?: "(none)"}")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogOut) { Text("Log out") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onClearSetup) { Text("Clear saved setup") }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
fun BillingScreen(route: String, onBackToHome: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Billing") }

    Column(Modifier.padding(24.dp)) {
        Text("Billing", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
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
        Text("Route not found", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("The CTA requested an unmapped route: \"$route\"")
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGoBack) { Text("Go back") }
    }
}

@Composable
fun InterceptorDialog(formId: String, onResult: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text("Interceptor: $formId") },
        text = { Text("onBeforeShowForm fired for this form id. Allow the SDK to render it, or deny to simulate a native replacement UI.") },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { onResult(false) }) { Text("Deny") } },
    )
}
