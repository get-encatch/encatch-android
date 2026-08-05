package com.encatch.androidtester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
            EncatchTesterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
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

    // Shared by Setup's "Save & continue" and the auto-init below — a real host app calls
    // Encatch.init once at startup; this tester must do the same on every process start, not
    // just the first-run Setup pass (identifyUser/showForm silently no-op un-initialized).
    suspend fun initializeSdk() {
        Encatch.init(
            prefs.apiKey.orEmpty(),
            EncatchConfig(
                apiBaseUrl = prefs.apiBaseUrl ?: prefs.environment.apiBaseUrl,
                webHost = prefs.webHost ?: prefs.environment.webHost,
                debugMode = true,
                // Unconditionally blocks the configured interceptor form id and queues it for
                // the InterceptorCarousel — demonstrates fully replacing the SDK's modal with
                // a custom-rendered native form.
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
    }

    // Auto-init on relaunch: mirrors the iOS testers — with setup already complete the app
    // skips the Setup screen, so init must happen here instead of Setup's onContinue.
    LaunchedEffect(Unit) {
        if (prefs.isSetupComplete && !Encatch.isInitialized) initializeSdk()
    }

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
                        initializeSdk()
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
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    TopAppBar(
                        title = { Text(tab.label, fontWeight = FontWeight.Bold) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            titleContentColor = MaterialTheme.colorScheme.onBackground,
                            actionIconContentColor = MaterialTheme.colorScheme.onBackground,
                        ),
                        actions = {
                            TextButton(onClick = { cycleTheme() }) {
                                Text(
                                    currentTheme.name.lowercase().replaceFirstChar { it.uppercase() },
                                    color = MaterialTheme.colorScheme.ink,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            TextButton(onClick = { logOut() }) {
                                Text(
                                    "Logout",
                                    color = MaterialTheme.colorScheme.ink,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        },
                    )
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                    ) {
                        TesterTab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = {
                                    Icon(
                                        t.icon,
                                        contentDescription = t.label,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                                label = {
                                    Text(
                                        t.label,
                                        fontSize = 10.sp,
                                        fontWeight = if (tab == t) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.ink,
                                    selectedTextColor = MaterialTheme.colorScheme.ink,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    indicatorColor = MaterialTheme.colorScheme.inkSoft,
                                ),
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
                        TesterTab.LOGS -> LogsScreen()
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

/** Presentation-only glyph for each bottom-nav destination — mirrors the iOS tab bar. */
private val TesterTab.icon: ImageVector
    get() = when (this) {
        TesterTab.HOME -> Icons.Filled.Home
        TesterTab.EVENTS -> Icons.Filled.PlayArrow
        TesterTab.LOGS -> Icons.AutoMirrored.Filled.List
        TesterTab.SETTINGS -> Icons.Filled.Settings
        TesterTab.INLINE_ANY -> Icons.Filled.KeyboardArrowDown
        TesterTab.INLINE_EXACT -> Icons.Filled.Place
    }

private fun TestUser.toTraits(): UserTraits? {
    val fields = buildMap<String, kotlinx.serialization.json.JsonElement> {
        if (email.isNotBlank()) put("email", JsonPrimitive(email))
        if (displayName.isNotBlank()) put("display_name", JsonPrimitive(displayName))
    }
    return if (fields.isEmpty()) null else UserTraits(set = fields)
}
