package com.encatch.composetester

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig
import com.encatch.sdk.EventType
import com.encatch.sdk.compose.EncatchInlineForm
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The whole tester app in one shared `commonMain` Compose UI — the simplest of the four testers,
 * since `:compose-sdk` needs no per-platform screen code at all beyond a tiny entry point
 * (`MainActivity.kt` on Android, `ComposeTesterViewController.kt` on iOS). Modeled on
 * `encatch-android-tester`'s screen set, minus two things `:compose-sdk` doesn't support yet:
 *  - No interceptor screen: `EncatchConfig` here (from `:kmp-sdk`, which `:compose-sdk` reuses)
 *    has no `onBeforeShowForm`.
 *  - No wildcard inline slot: `EncatchInlineForm(formId: String, ...)` takes a non-null `formId`
 *    — only the exact-match case is exposed as a composable.
 */
@Composable
fun TesterApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TesterScreens()
        }
    }
}

@Composable
private fun TesterScreens() {
    var screen by remember {
        mutableStateOf<Screen>(if (TesterPrefs.isSetupComplete) Screen.Login else Screen.Setup)
    }
    var lastEvent by remember { mutableStateOf("No events yet") }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        val unsubscribe = Encatch.on { eventType, payload ->
            lastEvent = "${eventType.wireValue} (formId=${payload.formId})"
            if (eventType == EventType.FORM_CTA_TRIGGERED) {
                val action = (payload.data?.get("action") as? JsonPrimitive)?.contentOrNull
                val route = (payload.data?.get("route") as? JsonPrimitive)?.contentOrNull
                if (action == "app_navigate") {
                    screen = when (route) {
                        "billing", "billing/upgrade" -> Screen.Billing(route)
                        else -> Screen.RouteNotFound(route ?: "(none)")
                    }
                }
            }
        }
        onDispose { unsubscribe() }
    }

    when (val current = screen) {
        Screen.Setup -> SetupScreen(
            onContinue = { apiKey, formId, baseUrl, webHost ->
                TesterPrefs.apiKey = apiKey
                TesterPrefs.formId = formId
                TesterPrefs.apiBaseUrl = baseUrl.ifBlank { null }
                TesterPrefs.webHost = webHost.ifBlank { null }
                scope.launch {
                    Encatch.init(
                        apiKey,
                        EncatchConfig(apiBaseUrl = TesterPrefs.apiBaseUrl, webHost = TesterPrefs.webHost, debugMode = true),
                    )
                    screen = Screen.Login
                }
            },
        )

        Screen.Login -> LoginScreen(
            initialUserName = TesterPrefs.userName.orEmpty(),
            onLogIn = { userName ->
                TesterPrefs.userName = userName
                scope.launch {
                    Encatch.identifyUser(userName)
                    screen = Screen.Home
                }
            },
        )

        Screen.Home -> HomeScreen(
            lastEvent = lastEvent,
            onShowModalForm = { scope.launch { Encatch.showForm(TesterPrefs.formId.orEmpty()) } },
            onNavigate = { screen = it },
        )

        Screen.Events -> EventsScreen(
            onTrack = { name -> scope.launch { Encatch.trackEvent(name) } },
            onBack = { screen = Screen.Home },
        )

        Screen.Inline -> InlineScreen(
            formId = TesterPrefs.formId.orEmpty(),
            onShowExact = { scope.launch { Encatch.showForm(TesterPrefs.formId.orEmpty()) } },
            onBack = { screen = Screen.Home },
        )

        Screen.Settings -> SettingsScreen(
            onLogOut = {
                scope.launch {
                    Encatch.resetUser()
                    screen = Screen.Login
                }
            },
            onClearSetup = {
                scope.launch {
                    Encatch.clearAll()
                    TesterPrefs.clear()
                    screen = Screen.Setup
                }
            },
            onBack = { screen = Screen.Home },
        )

        is Screen.Billing -> BillingScreen(route = current.route, onBackToHome = { screen = Screen.Home })

        is Screen.RouteNotFound -> RouteNotFoundScreen(route = current.route, onGoBack = { screen = Screen.Home })
    }
}

@Composable
private fun SetupScreen(onContinue: (apiKey: String, formId: String, baseUrl: String, webHost: String) -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    var formId by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var webHost by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Encatch Compose Tester — Setup", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Enter your own API key and default form id. Saved locally on this device — this same build works for any tester or environment.")
        Spacer()
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key *") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(formId, { formId = it }, label = { Text("Default form id (feedback config) *") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("API base URL (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(webHost, { webHost = it }, label = { Text("Web host (optional)") }, modifier = Modifier.fillMaxWidth())
        Spacer()
        Button(
            onClick = { onContinue(apiKey.trim(), formId.trim(), baseUrl.trim(), webHost.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        ) { Text("Save & continue") }
    }
}

@Composable
private fun LoginScreen(initialUserName: String, onLogIn: (String) -> Unit) {
    var userName by remember { mutableStateOf(initialUserName) }

    Column(Modifier.padding(24.dp)) {
        Text("Log in", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Mock login — calls Encatch.identifyUser(username).")
        Spacer()
        OutlinedTextField(userName, { userName = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
        Spacer()
        Button(onClick = { onLogIn(userName.trim()) }, enabled = userName.isNotBlank()) { Text("Log in") }
    }
}

@Composable
private fun HomeScreen(lastEvent: String, onShowModalForm: () -> Unit, onNavigate: (Screen) -> Unit) {
    LaunchedEffect(Unit) {
        Encatch.trackScreen("Home")
        Encatch.trackEvent("home_viewed")
    }

    Column(Modifier.padding(24.dp)) {
        Text("Home", style = MaterialTheme.typography.headlineSmall)
        Spacer(8)
        Text("Last event: $lastEvent")
        Spacer()
        Button(onClick = onShowModalForm) { Text("Show form (modal)") }
        Spacer()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { onNavigate(Screen.Events) }) { Text("Events") }
            TextButton(onClick = { onNavigate(Screen.Inline) }) { Text("Inline") }
            TextButton(onClick = { onNavigate(Screen.Settings) }) { Text("Settings") }
        }
    }
}

@Composable
private fun EventsScreen(onTrack: (String) -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }

    Column(Modifier.padding(24.dp)) {
        Text("Events", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed").forEach { name ->
            Button(onClick = { onTrack(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(name)
            }
        }
        Spacer()
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun InlineScreen(formId: String, onShowExact: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Inline") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Inline form", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Exact (claims \"$formId\") — :compose-sdk's EncatchInlineForm only supports exact-match form ids, no wildcard slot.")
        EncatchInlineForm(formId = formId, modifier = Modifier.fillMaxWidth().height(320.dp))
        Spacer()
        Button(onClick = onShowExact) { Text("Show inline form") }
        Spacer()
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun SettingsScreen(onLogOut: () -> Unit, onClearSetup: () -> Unit, onBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }

    Column(Modifier.padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Form id: ${TesterPrefs.formId}")
        Text("API base URL: ${TesterPrefs.apiBaseUrl ?: "(default)"}")
        Text("Web host: ${TesterPrefs.webHost ?: "(default)"}")
        Spacer()
        Button(onClick = onLogOut) { Text("Log out") }
        Spacer(8)
        Button(onClick = onClearSetup) { Text("Clear saved setup") }
        Spacer()
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun BillingScreen(route: String, onBackToHome: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Billing") }

    Column(Modifier.padding(24.dp)) {
        Text("Billing", style = MaterialTheme.typography.headlineSmall)
        Spacer(8)
        Text("Reached via CTA app_navigate route: \"$route\"")
        Spacer()
        Button(onClick = onBackToHome) { Text("Back to home") }
    }
}

@Composable
private fun RouteNotFoundScreen(route: String, onGoBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("RouteNotFound") }

    Column(Modifier.padding(24.dp)) {
        Text("Route not found", style = MaterialTheme.typography.headlineSmall)
        Spacer(8)
        Text("The CTA requested an unmapped route: \"$route\"")
        Spacer()
        Button(onClick = onGoBack) { Text("Go back") }
    }
}

@Composable
private fun Spacer(dp: Int = 16) {
    androidx.compose.foundation.layout.Spacer(Modifier.height(dp.dp))
}
