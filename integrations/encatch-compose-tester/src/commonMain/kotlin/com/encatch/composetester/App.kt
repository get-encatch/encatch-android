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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.encatch.sdk.Theme
import com.encatch.sdk.UserTraits
import com.encatch.sdk.compose.EncatchInlineForm
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The whole tester app in one shared `commonMain` Compose UI — the simplest of the four testers,
 * since `:compose-sdk` needs no per-platform screen code at all beyond a tiny entry point
 * (`MainActivity.kt` on Android, `ComposeTesterViewController.kt` on iOS). Modeled on the richer
 * `encatch-flutter-tester` reference app, minus one thing `:compose-sdk` doesn't support yet: no
 * wildcard inline slot — `EncatchInlineForm(formId: String, ...)` takes a non-null `formId`, only
 * the exact-match case is exposed as a composable (see README's Known gap).
 */
@Composable
fun TesterApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            TesterScreens()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TesterScreens() {
    var screen by remember {
        mutableStateOf<Screen>(if (TesterPrefs.isSetupComplete) Screen.Login else Screen.Setup)
    }
    var tab by remember { mutableStateOf(TesterTab.HOME) }
    var lastEvent by remember { mutableStateOf("No events yet") }
    var selectedUsername by remember { mutableStateOf(TesterPrefs.userName) }
    var savedUsers by remember { mutableStateOf(TestUsersStore.list()) }
    var currentTheme by remember { mutableStateOf(Theme.SYSTEM) }
    var blockedForms by remember { mutableStateOf(listOf<BlockedFormItem>()) }
    var openedForm by remember { mutableStateOf<BlockedFormItem?>(null) }
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

    fun cycleTheme() {
        currentTheme = when (currentTheme) {
            Theme.SYSTEM -> Theme.LIGHT
            Theme.LIGHT -> Theme.DARK
            Theme.DARK -> Theme.SYSTEM
        }
        Encatch.setTheme(currentTheme)
    }

    fun logOut() {
        scope.launch {
            Encatch.resetUser()
            selectedUsername = null
            screen = Screen.Login
        }
    }

    when (val current = screen) {
        Screen.Setup -> SetupScreen(
            initialEnvironment = TesterPrefs.environment,
            onContinue = { environment, apiKey, formId, interceptorFormId ->
                TesterPrefs.environment = environment
                TesterPrefs.apiKey = apiKey
                TesterPrefs.formId = formId
                TesterPrefs.apiBaseUrl = environment.apiBaseUrl
                TesterPrefs.webHost = environment.webHost
                TesterPrefs.interceptorFormId = interceptorFormId.ifBlank { null }
                scope.launch {
                    Encatch.init(
                        apiKey,
                        EncatchConfig(
                            apiBaseUrl = environment.apiBaseUrl,
                            webHost = environment.webHost,
                            debugMode = true,
                            // Unconditionally blocks the configured interceptor form id and queues
                            // it for the InterceptorCarousel — demonstrates fully replacing the
                            // SDK's modal with a custom-rendered native form.
                            onBeforeShowForm = { payload ->
                                if (payload.formId == TesterPrefs.interceptorFormId) {
                                    blockedForms = blockedForms + BlockedFormItem(
                                        formId = payload.formId,
                                        title = payload.formId,
                                        formConfigJson = payload.formConfigJson,
                                    )
                                    false
                                } else {
                                    true
                                }
                            },
                        ),
                    )
                    screen = Screen.Login
                }
            },
        )

        Screen.Login -> LoginScreen(
            savedUsers = savedUsers,
            selectedUsername = selectedUsername,
            onSelectUser = { user -> selectedUsername = user.username },
            onSaveNewUser = { user ->
                TestUsersStore.add(user)
                savedUsers = TestUsersStore.list()
                selectedUsername = user.username
            },
            onEditProfile = { username -> screen = Screen.EditProfile(username) },
            onIdentify = {
                val username = selectedUsername ?: return@LoginScreen
                val user = savedUsers.find { it.username == username }
                scope.launch {
                    Encatch.identifyUser(username, traits = user?.toTraits())
                    TesterPrefs.userName = username
                    tab = TesterTab.HOME
                    screen = Screen.Main
                }
            },
            onChangeSetup = {
                scope.launch {
                    Encatch.resetUser()
                    TesterPrefs.clear()
                    screen = Screen.Setup
                }
            },
        )

        is Screen.EditProfile -> {
            val user = savedUsers.find { it.username == current.username } ?: TestUser(current.username)
            EditProfileScreen(
                username = current.username,
                initialEmail = user.email,
                initialDisplayName = user.displayName,
                onSave = { email, displayName ->
                    val updated = TestUser(current.username, email, displayName)
                    TestUsersStore.update(updated)
                    savedUsers = TestUsersStore.list()
                    if (selectedUsername == current.username) {
                        scope.launch { Encatch.identifyUser(current.username, traits = updated.toTraits()) }
                    }
                    screen = Screen.Login
                },
                onBack = { screen = Screen.Login },
            )
        }

        Screen.Main -> Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tab.label) },
                    actions = {
                        TextButton(onClick = { cycleTheme() }) { Text(currentTheme.name) }
                        TextButton(onClick = { logOut() }) { Text("Logout") }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    TesterTab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {},
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                when (tab) {
                    TesterTab.HOME -> HomeScreen(
                        userName = TesterPrefs.userName,
                        lastEvent = lastEvent,
                        interceptorFormId = TesterPrefs.interceptorFormId,
                        onShowModalForm = { scope.launch { Encatch.showForm(TesterPrefs.formId.orEmpty()) } },
                        onShowPrefilledForm = {
                            scope.launch {
                                Encatch.addToResponse("prefill-question", "hello")
                                Encatch.showForm(TesterPrefs.formId.orEmpty())
                            }
                        },
                        onShowInterceptorForm = { id -> scope.launch { Encatch.showForm(id) } },
                        onEditProfile = { TesterPrefs.userName?.let { screen = Screen.EditProfile(it) } },
                    )
                    TesterTab.EVENTS -> EventsScreen(
                        onTrackEvent = { name -> scope.launch { Encatch.trackEvent(name) } },
                        onTrackScreen = { name -> scope.launch { Encatch.trackScreen(name) } },
                    )
                    TesterTab.SETTINGS -> SettingsScreen(
                        onSetLocale = { Encatch.setLocale("fr-FR") },
                        onSetCountry = { Encatch.setCountry("FR") },
                        onChangeSetup = {
                            scope.launch {
                                Encatch.resetUser()
                                TesterPrefs.clear()
                                screen = Screen.Setup
                            }
                        },
                    )
                    TesterTab.INLINE_EXACT -> InlineExactScreen(
                        formId = TesterPrefs.formId.orEmpty(),
                        onShowExact = { scope.launch { Encatch.showForm(TesterPrefs.formId.orEmpty()) } },
                    )
                }

                InterceptorCarousel(
                    items = blockedForms,
                    onOpen = { item -> openedForm = item },
                    onDismiss = { formId -> blockedForms = blockedForms.filterNot { it.formId == formId } },
                )
            }
        }

        is Screen.Billing -> BillingScreen(route = current.route, onBackToHome = { screen = Screen.Main })

        is Screen.RouteNotFound -> RouteNotFoundScreen(route = current.route, onGoBack = { screen = Screen.Main })
    }

    openedForm?.let { item ->
        NativeFormModal(
            item = item,
            onClose = {
                blockedForms = blockedForms.filterNot { it.formId == item.formId }
                openedForm = null
            },
        )
    }
}

private fun TestUser.toTraits(): UserTraits? {
    val fields = buildMap<String, JsonElement> {
        if (email.isNotBlank()) put("email", JsonPrimitive(email))
        if (displayName.isNotBlank()) put("display_name", JsonPrimitive(displayName))
    }
    return if (fields.isEmpty()) null else UserTraits(set = fields)
}

@Composable
private fun SetupScreen(
    initialEnvironment: TesterEnvironment,
    onContinue: (environment: TesterEnvironment, apiKey: String, formId: String, interceptorFormId: String) -> Unit,
) {
    var environment by remember { mutableStateOf(initialEnvironment) }
    var apiKey by remember { mutableStateOf("") }
    var formId by remember { mutableStateOf("") }
    var interceptorFormId by remember { mutableStateOf("") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Encatch Compose Tester — Setup", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Enter your own API key and default form id. Saved locally on this device — this same build works for any tester or environment.")
        Spacer()

        Text("Environment", style = MaterialTheme.typography.labelLarge)
        Spacer(4)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            TesterEnvironment.entries.forEachIndexed { index, env ->
                SegmentedButton(
                    selected = environment == env,
                    onClick = { environment = env },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = TesterEnvironment.entries.size),
                ) { Text(env.label) }
            }
        }
        Text("${environment.apiBaseUrl} · ${environment.webHost}", style = MaterialTheme.typography.bodySmall)

        Spacer()
        OutlinedTextField(apiKey, { apiKey = it }, label = { Text("API key *") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(formId, { formId = it }, label = { Text("Default form id (feedback config) *") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(
            interceptorFormId,
            { interceptorFormId = it },
            label = { Text("Interceptor test form id (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer()
        Button(
            onClick = { onContinue(environment, apiKey.trim(), formId.trim(), interceptorFormId.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        ) { Text("Save & continue") }
    }
}

@Composable
private fun LoginScreen(
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
        Spacer(8)
        Text("Mock login — calls Encatch.identifyUser(username). Saved users are local to this tester, independent of the SDK.")
        Spacer()

        Text("Saved users", style = MaterialTheme.typography.labelLarge)
        Spacer(4)
        if (savedUsers.isEmpty()) {
            Text("No saved users yet.", style = MaterialTheme.typography.bodySmall)
        }
        savedUsers.forEach { user ->
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { onSelectUser(user) }) {
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

        Spacer(8)
        if (!showNewUserForm) {
            TextButton(onClick = { showNewUserForm = true }) { Text("+ New user") }
        } else {
            OutlinedTextField(newUsername, { newUsername = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            Spacer(8)
            OutlinedTextField(newEmail, { newEmail = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
            Spacer(8)
            OutlinedTextField(newDisplayName, { newDisplayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
            Spacer(8)
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
            Spacer()
            TextButton(onClick = { onEditProfile(selectedUsername) }) { Text("Edit profile before sign in") }
        }

        Spacer()
        Button(onClick = onIdentify, enabled = selectedUsername != null) { Text("Identify user") }
        Spacer(8)
        TextButton(onClick = onChangeSetup) { Text("Change API key & setup") }
    }
}

@Composable
private fun EditProfileScreen(
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
        Spacer(8)
        Text("Username: $username")
        Spacer()
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        Spacer(8)
        OutlinedTextField(displayName, { displayName = it }, label = { Text("Display name") }, modifier = Modifier.fillMaxWidth())
        Spacer()
        Button(onClick = { onSave(email.trim(), displayName.trim()) }) { Text("Save & identify") }
        Spacer(8)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
private fun HomeScreen(
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
            Spacer(4)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Signed in as $userName")
                TextButton(onClick = onEditProfile) { Text("Edit profile") }
            }
        }
        Spacer(8)
        Text("Last event: $lastEvent")
        Spacer()
        Button(onClick = onShowModalForm) { Text("Show Form") }
        Spacer(8)
        Button(onClick = onShowPrefilledForm) { Text("Show Form (prefilled)") }
        if (!interceptorFormId.isNullOrBlank()) {
            Spacer(8)
            Button(onClick = { onShowInterceptorForm(interceptorFormId) }) { Text("Show Form (interceptor test)") }
        }
    }
}

private val TRACK_EVENT_PRESETS = listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed")
private val TRACK_SCREEN_PRESETS = listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test")

@Composable
private fun EventsScreen(onTrackEvent: (String) -> Unit, onTrackScreen: (String) -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }
    var customEvent by remember { mutableStateOf("test_event") }
    var customScreen by remember { mutableStateOf("/dashboard/encatch-test") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Events", style = MaterialTheme.typography.headlineSmall)
        Spacer()

        Text("trackEvent presets", style = MaterialTheme.typography.labelLarge)
        TRACK_EVENT_PRESETS.forEach { name ->
            Button(onClick = { onTrackEvent(name) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(name) }
        }
        Spacer(8)
        OutlinedTextField(customEvent, { customEvent = it }, label = { Text("Custom event") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onTrackEvent(customEvent.trim()) }, enabled = customEvent.isNotBlank()) { Text("Fire") }

        Spacer()
        Text("trackScreen presets", style = MaterialTheme.typography.labelLarge)
        TRACK_SCREEN_PRESETS.forEach { path ->
            Button(onClick = { onTrackScreen(path) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(path) }
        }
        Spacer(8)
        OutlinedTextField(customScreen, { customScreen = it }, label = { Text("Custom screen") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { onTrackScreen(customScreen.trim()) }, enabled = customScreen.isNotBlank()) { Text("Track") }
    }
}

@Composable
private fun InlineExactScreen(formId: String, onShowExact: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineExact") }

    Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Inline (Exact)", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Claims \"$formId\" — :compose-sdk's EncatchInlineForm only supports exact-match form ids, no wildcard slot.")
        Button(onClick = onShowExact) { Text("Show Exact Form (renders inline below)") }
        Spacer()
        EncatchInlineForm(formId = formId, modifier = Modifier.fillMaxWidth().height(320.dp))
    }
}

@Composable
private fun SettingsScreen(onSetLocale: () -> Unit, onSetCountry: () -> Unit, onChangeSetup: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }

    Column(Modifier.padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Spacer()
        Text("Environment: ${TesterPrefs.environment.label}")
        Text("Form id: ${TesterPrefs.formId}")
        Text("API base URL: ${TesterPrefs.apiBaseUrl ?: "(default)"}")
        Text("Web host: ${TesterPrefs.webHost ?: "(default)"}")
        Text("Interceptor form id: ${TesterPrefs.interceptorFormId ?: "(none)"}")
        Spacer()
        Button(onClick = onSetLocale) { Text("Set Locale → fr-FR") }
        Spacer(8)
        Button(onClick = onSetCountry) { Text("Set Country → FR") }
        Spacer()
        TextButton(onClick = onChangeSetup) { Text("Change API key & setup") }
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
