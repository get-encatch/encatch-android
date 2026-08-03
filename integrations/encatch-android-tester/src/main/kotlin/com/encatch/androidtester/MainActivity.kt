package com.encatch.androidtester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.encatch.core.Encatch
import com.encatch.core.EncatchConfig
import com.encatch.core.EventType
import com.encatch.core.Theme
import com.encatch.core.UserTraits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Single-activity tester app for `:core`/`:android`, modeled on the richer `encatch-flutter-tester`
 * reference: runtime Setup, saved local test-user profiles, bottom-nav (Home/Events/Settings/
 * Inline-Any/Inline-Exact), header theme cycling, and an interceptor carousel that hands blocked
 * forms off to a fully custom native form renderer instead of the SDK's WebView.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = TesterPrefs(this)
        val usersStore = TestUsersStore(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TesterApp(prefs = prefs, usersStore = usersStore, scope = lifecycleScope)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TesterApp(prefs: TesterPrefs, usersStore: TestUsersStore, scope: CoroutineScope) {
    var screen by remember {
        mutableStateOf<Screen>(if (prefs.isSetupComplete) Screen.Login else Screen.Setup)
    }
    var tab by remember { mutableStateOf(TesterTab.HOME) }
    var lastEvent by remember { mutableStateOf("No events yet") }
    var selectedUsername by remember { mutableStateOf(prefs.userName) }
    var savedUsers by remember { mutableStateOf(usersStore.list()) }
    var currentTheme by remember { mutableStateOf(Theme.SYSTEM) }
    var blockedForms by remember { mutableStateOf(listOf<BlockedFormItem>()) }
    var openedForm by remember { mutableStateOf<BlockedFormItem?>(null) }

    // Registered once for the process lifetime, same as a real host app would at startup.
    DisposableEffect(Unit) {
        val unsubscribe = Encatch.on { eventType, payload ->
            lastEvent = "${eventType.wireValue} (formId=${payload.formId})"
            if (eventType == EventType.FORM_CTA_TRIGGERED) {
                val data = payload.data
                val action = (data?.get("action") as? JsonPrimitive)?.content
                val route = (data?.get("route") as? JsonPrimitive)?.content
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

    Box(Modifier.fillMaxSize()) {
        when (val current = screen) {
            Screen.Setup -> SetupScreen(
                initialEnvironment = prefs.environment,
                onContinue = { environment, apiKey, formId, interceptorFormId ->
                    prefs.environment = environment
                    prefs.apiKey = apiKey
                    prefs.formId = formId
                    prefs.apiBaseUrl = environment.apiBaseUrl
                    prefs.webHost = environment.webHost
                    prefs.interceptorFormId = interceptorFormId.ifBlank { null }
                    scope.launch {
                        Encatch.init(
                            apiKey,
                            EncatchConfig(
                                apiBaseUrl = environment.apiBaseUrl,
                                webHost = environment.webHost,
                                debugMode = true,
                                // Unconditionally blocks the configured interceptor form id and
                                // queues it for the InterceptorCarousel — demonstrates fully
                                // replacing the SDK's modal with a custom-rendered native form.
                                onBeforeShowForm = { payload ->
                                    if (payload.formId == prefs.interceptorFormId) {
                                        val title = (payload.formConfig.formConfiguration
                                            ?.get("formTitle") as? JsonPrimitive)?.content ?: payload.formId
                                        blockedForms = blockedForms + BlockedFormItem(
                                            formId = payload.formId,
                                            title = title,
                                            questionnaireFields = payload.formConfig.questionnaireFields,
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
                    usersStore.add(user)
                    savedUsers = usersStore.list()
                    selectedUsername = user.username
                },
                onEditProfile = { username -> screen = Screen.EditProfile(username) },
                onIdentify = {
                    val username = selectedUsername ?: return@LoginScreen
                    val user = savedUsers.find { it.username == username }
                    scope.launch {
                        Encatch.identifyUser(username, traits = user?.toTraits())
                        prefs.userName = username
                        screen = Screen.Main
                        tab = TesterTab.HOME
                    }
                },
                onChangeSetup = {
                    scope.launch {
                        Encatch.resetUser()
                        prefs.clear()
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
                        usersStore.update(updated)
                        savedUsers = usersStore.list()
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
                            userName = prefs.userName,
                            lastEvent = lastEvent,
                            interceptorFormId = prefs.interceptorFormId,
                            onShowModalForm = { scope.launch { Encatch.showForm(prefs.formId.orEmpty()) } },
                            onShowPrefilledForm = {
                                scope.launch {
                                    Encatch.addToResponse("prefill-question", "hello")
                                    Encatch.showForm(prefs.formId.orEmpty())
                                }
                            },
                            onShowInterceptorForm = { id -> scope.launch { Encatch.showForm(id) } },
                            onEditProfile = { prefs.userName?.let { screen = Screen.EditProfile(it) } },
                        )
                        TesterTab.EVENTS -> EventsScreen(
                            onTrackEvent = { name -> scope.launch { Encatch.trackEvent(name) } },
                            onTrackScreen = { name -> scope.launch { Encatch.trackScreen(name) } },
                        )
                        TesterTab.SETTINGS -> SettingsScreen(
                            prefs = prefs,
                            onSetLocale = { Encatch.setLocale("fr-FR") },
                            onSetCountry = { Encatch.setCountry("FR") },
                            onChangeSetup = {
                                scope.launch {
                                    Encatch.resetUser()
                                    prefs.clear()
                                    screen = Screen.Setup
                                }
                            },
                        )
                        TesterTab.INLINE_ANY -> InlineAnyScreen(
                            onShowWildcard = { id -> scope.launch { Encatch.showForm(id) } },
                            onTriggerFallback = { scope.launch { Encatch.showForm("modal-fallback-demo") } },
                        )
                        TesterTab.INLINE_EXACT -> InlineExactScreen(
                            exactFormId = prefs.formId.orEmpty(),
                            onShowExact = { scope.launch { Encatch.showForm(prefs.formId.orEmpty()) } },
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
}

private fun TestUser.toTraits(): UserTraits? {
    val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
        if (email.isNotBlank()) put("email", JsonPrimitive(email))
        if (displayName.isNotBlank()) put("display_name", JsonPrimitive(displayName))
    }
    return if (fields.isEmpty()) null else UserTraits(set = fields)
}
