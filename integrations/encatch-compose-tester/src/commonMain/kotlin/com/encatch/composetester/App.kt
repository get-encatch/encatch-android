package com.encatch.composetester

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer as LayoutSpacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * Visual language lives in `Theme.kt` and mirrors the iOS tester's Uber-style monochrome design.
 */
@Composable
fun TesterApp() {
    EncatchTesterTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
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
    var appliedLocale by remember { mutableStateOf<String?>(null) }
    var appliedCountry by remember { mutableStateOf<String?>(null) }
    var lastEvent by remember { mutableStateOf("No events yet") }
    var selectedUsername by remember { mutableStateOf(TesterPrefs.userName) }
    var savedUsers by remember { mutableStateOf(TestUsersStore.list()) }
    var currentTheme by remember { mutableStateOf(Theme.SYSTEM) }
    var blockedForms by remember { mutableStateOf(listOf<BlockedFormItem>()) }
    var openedForm by remember { mutableStateOf<BlockedFormItem?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        NetworkLogStore.install()
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

    // Shared by Setup's "Save & continue" and the auto-init below — a real host app calls
    // Encatch.init once at startup; this tester must do the same on every process start, not
    // just the first-run Setup pass (identifyUser/showForm silently no-op un-initialized).
    suspend fun initializeSdk() {
        Encatch.init(
            TesterPrefs.apiKey.orEmpty(),
            EncatchConfig(
                apiBaseUrl = TesterPrefs.apiBaseUrl ?: TesterPrefs.environment.apiBaseUrl,
                webHost = TesterPrefs.webHost ?: TesterPrefs.environment.webHost,
                debugMode = true,
                // Unconditionally blocks the configured interceptor form id and queues it for
                // the InterceptorCarousel — demonstrates fully replacing the SDK's modal with
                // a custom-rendered native form.
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
    }

    // Auto-init on relaunch: with setup already complete the app skips the Setup screen, so
    // init must happen here instead of Setup's onContinue (parity with the native testers).
    LaunchedEffect(Unit) {
        if (TesterPrefs.isSetupComplete && !Encatch.isInitialized) initializeSdk()
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
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(tab.label, fontWeight = FontWeight.Bold, fontSize = 17.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    actions = {
                        QuietTextButton(
                            text = currentTheme.name.lowercase().replaceFirstChar { it.uppercase() },
                            onClick = { cycleTheme() },
                            color = MaterialTheme.colorScheme.ink,
                            modifier = Modifier.width(76.dp),
                        )
                        QuietTextButton(
                            text = "Log out",
                            onClick = { logOut() },
                            color = MaterialTheme.colorScheme.ink,
                            modifier = Modifier.width(76.dp),
                        )
                    },
                )
            },
            bottomBar = {
                TesterTabBar(selected = tab, onSelect = { tab = it })
            },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                Box(Modifier.weight(1f, fill = true)) {
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
                        TesterTab.LOGS -> LogsScreen()
                        TesterTab.SETTINGS -> SettingsScreen(
                            appliedLocale = appliedLocale,
                            appliedCountry = appliedCountry,
                            onSetLocale = { Encatch.setLocale(it); appliedLocale = it },
                            onSetCountry = { Encatch.setCountry(it); appliedCountry = it },
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
                        TesterTab.INLINE_ANY -> InlineAnyScreen(
                            onShowWildcard = { id -> scope.launch { Encatch.showForm(id) } },
                            onTriggerFallback = { scope.launch { Encatch.showForm("modal-fallback-demo") } },
                        )
                    }
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

/** Monochrome bottom bar: selected ink + semibold, unselected gray, no indicator pill. */
@Composable
private fun TesterTabBar(selected: TesterTab, onSelect: (TesterTab) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(cs.background)) {
        HorizontalDivider(thickness = 1.dp, color = cs.onBackground.copy(alpha = 0.08f))
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(top = 10.dp, bottom = 8.dp)) {
            TesterTab.entries.forEach { t ->
                val active = selected == t
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(t) },
                        )
                        .padding(vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        t.shortLabel,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) cs.ink else cs.onSurfaceVariant,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Centered hero block used on Setup/Login: brand mark, bold title, secondary caption. */
@Composable
private fun HeroHeader(title: String, subtitle: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark()
        Spacer(10)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(6)
        Text(
            subtitle,
            fontSize = 13.sp,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
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
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        HeroHeader(
            title = "Encatch Tester",
            subtitle = "Enter your API key and default form id. Saved locally on this device — the same build works for any tester or environment.",
        )
        Spacer(24)

        SectionHeader("Environment")
        Spacer(8)
        TesterCard {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TesterEnvironment.entries.forEachIndexed { index, env ->
                    SegmentedButton(
                        selected = environment == env,
                        onClick = { environment = env },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = TesterEnvironment.entries.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = cs.ink,
                            activeContentColor = cs.background,
                            activeBorderColor = cs.outline,
                            inactiveContainerColor = cs.background.copy(alpha = 0f),
                            inactiveContentColor = cs.onBackground,
                            inactiveBorderColor = cs.outline,
                        ),
                        icon = {},
                    ) { Text(env.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Spacer(10)
            Text(
                "${environment.apiBaseUrl} · ${environment.webHost}",
                fontSize = 12.sp,
                color = cs.onSurfaceVariant,
            )
        }

        Spacer(20)
        SectionHeader("Credentials")
        Spacer(8)
        TesterCard {
            FieldLabel("API key", required = true)
            Spacer(6)
            FilledField(apiKey, { apiKey = it }, placeholder = "en_dev_…")
            Spacer(14)
            FieldLabel("Default form id (feedback config)", required = true)
            Spacer(6)
            FilledField(formId, { formId = it }, placeholder = "form id")
            Spacer(14)
            FieldLabel("Interceptor test form id (optional)")
            Spacer(6)
            FilledField(interceptorFormId, { interceptorFormId = it }, placeholder = "form id")
        }

        Spacer(24)
        PrimaryPillButton(
            text = "Save & continue",
            onClick = { onContinue(environment, apiKey.trim(), formId.trim(), interceptorFormId.trim()) },
            enabled = apiKey.isNotBlank() && formId.isNotBlank(),
        )
        Spacer(24)
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
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        HeroHeader(
            title = "Log in",
            subtitle = "Mock login — calls Encatch.identifyUser(username). Saved users are local to this tester, independent of the SDK.",
        )
        Spacer(24)

        SectionHeader("Saved users")
        Spacer(8)
        TesterCard {
            if (savedUsers.isEmpty()) {
                Text(
                    "No saved users yet — add one below.",
                    fontSize = 13.sp,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            savedUsers.forEach { user ->
                val selected = selectedUsername == user.username
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) cs.inkSoft else cs.onBackground.copy(alpha = 0.04f))
                        .clickable { onSelectUser(user) }
                        .padding(10.dp),
                ) {
                    InitialsAvatar(name = user.displayName.ifBlank { user.username })
                    LayoutSpacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.username, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onBackground)
                        val detail = listOf(user.displayName, user.email).filter { it.isNotBlank() }.joinToString(" · ")
                        if (detail.isNotEmpty()) {
                            Text(detail, fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
                        }
                    }
                    Text(
                        if (selected) "✓" else "○",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) cs.ink else cs.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }

            if (!showNewUserForm) {
                QuietTextButton(text = "+ New user", onClick = { showNewUserForm = true }, color = cs.ink)
            } else {
                Spacer(8)
                FilledField(newUsername, { newUsername = it }, placeholder = "Username")
                Spacer(10)
                FilledField(newEmail, { newEmail = it }, placeholder = "Email")
                Spacer(10)
                FilledField(newDisplayName, { newDisplayName = it }, placeholder = "Display name")
                Spacer(10)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryPillButton(
                        text = "Save user",
                        onClick = {
                            onSaveNewUser(TestUser(newUsername.trim(), newEmail.trim(), newDisplayName.trim()))
                            showNewUserForm = false
                            newUsername = ""; newEmail = ""; newDisplayName = ""
                        },
                        enabled = newUsername.isNotBlank(),
                        fullWidth = false,
                        modifier = Modifier.weight(1f),
                    )
                    QuietTextButton(
                        text = "Cancel",
                        onClick = { showNewUserForm = false },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(24)
        if (selectedUsername != null) {
            SecondaryPillButton(text = "Edit profile before sign in", onClick = { onEditProfile(selectedUsername) })
            Spacer(10)
        }
        PrimaryPillButton(text = "Identify user", onClick = onIdentify, enabled = selectedUsername != null)
        Spacer(4)
        QuietTextButton(text = "Change API key & setup", onClick = onChangeSetup)
        Spacer(24)
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
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            InitialsAvatar(name = displayName.ifBlank { username }, size = 64.dp)
            Spacer(10)
            Text("Edit profile", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
            Spacer(4)
            Text("@$username", fontSize = 14.sp, color = cs.onSurfaceVariant)
        }
        Spacer(24)

        SectionHeader("Profile traits")
        Spacer(8)
        TesterCard {
            FieldLabel("Email")
            Spacer(6)
            FilledField(email, { email = it }, placeholder = "name@example.com")
            Spacer(14)
            FieldLabel("Display name")
            Spacer(6)
            FilledField(displayName, { displayName = it }, placeholder = "Display name")
        }

        Spacer(24)
        PrimaryPillButton(text = "Save & identify", onClick = { onSave(email.trim(), displayName.trim()) })
        Spacer(4)
        QuietTextButton(text = "Back", onClick = onBack)
        Spacer(24)
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
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(16)
        if (!userName.isNullOrBlank()) {
            TesterCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InitialsAvatar(name = userName, size = 44.dp)
                    LayoutSpacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Signed in as", fontSize = 12.sp, color = cs.onSurfaceVariant)
                        Text(userName, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
                    }
                    Text(
                        "Edit profile",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onEditProfile)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            Spacer(20)
        }

        SectionHeader("Last SDK event")
        Spacer(8)
        TesterCard {
            Text(
                lastEvent,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = cs.onSurfaceVariant,
                maxLines = 2,
            )
        }

        Spacer(20)
        SectionHeader("Forms")
        Spacer(8)
        TesterCard {
            PrimaryPillButton(text = "Show Form", onClick = onShowModalForm)
            Spacer(10)
            SecondaryPillButton(text = "Show Form (prefilled)", onClick = onShowPrefilledForm)
            if (!interceptorFormId.isNullOrBlank()) {
                Spacer(10)
                SecondaryPillButton(
                    text = "Show Form (interceptor test)",
                    onClick = { onShowInterceptorForm(interceptorFormId) },
                )
            }
        }
        Spacer(24)
    }
}

private val TRACK_EVENT_PRESETS = listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed")
private val TRACK_SCREEN_PRESETS = listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventsScreen(onTrackEvent: (String) -> Unit, onTrackScreen: (String) -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Events") }
    var customEvent by remember { mutableStateOf("test_event") }
    var customScreen by remember { mutableStateOf("/dashboard/encatch-test") }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp)) {
        Spacer(16)
        SectionHeader("trackEvent presets")
        Spacer(8)
        TesterCard {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TRACK_EVENT_PRESETS.forEach { name ->
                    ChipButton(text = name, onClick = { onTrackEvent(name) })
                }
            }
            Spacer(12)
            HorizontalDivider(color = cs.onBackground.copy(alpha = 0.08f))
            Spacer(12)
            FieldLabel("Custom event")
            Spacer(6)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledField(customEvent, { customEvent = it }, placeholder = "event_name", modifier = Modifier.weight(1f))
                SecondaryPillButton(
                    text = "Fire",
                    onClick = { onTrackEvent(customEvent.trim()) },
                    enabled = customEvent.isNotBlank(),
                    fullWidth = false,
                )
            }
        }

        Spacer(20)
        SectionHeader("trackScreen presets")
        Spacer(8)
        TesterCard {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TRACK_SCREEN_PRESETS.forEach { path ->
                    ChipButton(text = path, onClick = { onTrackScreen(path) })
                }
            }
            Spacer(12)
            HorizontalDivider(color = cs.onBackground.copy(alpha = 0.08f))
            Spacer(12)
            FieldLabel("Custom screen")
            Spacer(6)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledField(customScreen, { customScreen = it }, placeholder = "/path", modifier = Modifier.weight(1f))
                SecondaryPillButton(
                    text = "Track",
                    onClick = { onTrackScreen(customScreen.trim()) },
                    enabled = customScreen.isNotBlank(),
                    fullWidth = false,
                )
            }
        }
        Spacer(24)
    }
}

@Composable
private fun InlineExactScreen(formId: String, onShowExact: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineExact") }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(16)
        TesterCard {
            Text(
                "Claims \"$formId\" — only showForm calls for this exact id render here.",
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(16)
        PrimaryPillButton(text = "Show Exact Form (renders inline below)", onClick = onShowExact)
        Spacer(16)
        // No fixed height — the SDK view self-sizes (skeleton placeholder, then live
        // form:resize values) on both platforms.
        EncatchInlineForm(
            formId = formId,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(CardCornerRadius)),
        )
        Spacer(24)
    }
}

@Composable
private fun InlineAnyScreen(onShowWildcard: (String) -> Unit, onTriggerFallback: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("InlineAny") }
    val cs = MaterialTheme.colorScheme
    var wildcardFormId by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(16)
        TesterCard {
            Text(
                "Wildcard slot (formId = null) — catches any form id not exactly claimed elsewhere.",
                fontSize = 13.sp,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(16)
        TesterCard {
            FieldLabel("Form id")
            Spacer(6)
            FilledField(wildcardFormId, { wildcardFormId = it }, placeholder = "form id")
            Spacer(12)
            PrimaryPillButton(
                text = "Show Form (renders inline below)",
                onClick = { onShowWildcard(wildcardFormId.trim()) },
                enabled = wildcardFormId.isNotBlank(),
            )
            QuietTextButton(
                "Trigger unmatched form → modal fallback",
                onClick = onTriggerFallback,
                color = cs.ink,
            )
        }
        Spacer(16)
        // Wildcard slot — no formId. Self-sizes the same way as the exact slot above.
        EncatchInlineForm(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(CardCornerRadius)),
        )
        Spacer(24)
    }
}

/**
 * Modal prompting for a locale/country code to switch the SDK to. Apply is disabled while the
 * input is blank; the applied value is echoed back in the Localization card so the click has
 * visible feedback (the SDK setters themselves are silent — they only affect the NEXT showForm).
 */
@Composable
private fun LocaleInputDialog(
    title: String,
    placeholder: String,
    initial: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                FilledField(input, { input = it }, placeholder = placeholder)
                Spacer(8)
                Text(
                    "Applies to the next form shown — an already-open form keeps its language.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(input.trim()); onDismiss() },
                enabled = input.isNotBlank(),
            ) { Text("Apply", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SettingsScreen(
    appliedLocale: String?,
    appliedCountry: String?,
    onSetLocale: (String) -> Unit,
    onSetCountry: (String) -> Unit,
    onChangeSetup: () -> Unit,
) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Settings") }
    val cs = MaterialTheme.colorScheme
    var showLocaleDialog by remember { mutableStateOf(false) }
    var showCountryDialog by remember { mutableStateOf(false) }

    if (showLocaleDialog) {
        LocaleInputDialog(
            title = "Set Locale",
            placeholder = "e.g. fr-FR, hi-IN",
            initial = appliedLocale ?: "",
            onApply = onSetLocale,
            onDismiss = { showLocaleDialog = false },
        )
    }
    if (showCountryDialog) {
        LocaleInputDialog(
            title = "Set Country",
            placeholder = "e.g. FR, IN",
            initial = appliedCountry ?: "",
            onApply = onSetCountry,
            onDismiss = { showCountryDialog = false },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Spacer(16)
        SectionHeader("Current configuration")
        Spacer(8)
        TesterCard {
            InfoRow("Environment", TesterPrefs.environment.label)
            SettingsDivider()
            InfoRow("Form id", TesterPrefs.formId ?: "—")
            SettingsDivider()
            InfoRow("API base URL", TesterPrefs.apiBaseUrl ?: "(default)")
            SettingsDivider()
            InfoRow("Web host", TesterPrefs.webHost ?: "(default)")
            SettingsDivider()
            InfoRow("Interceptor form id", TesterPrefs.interceptorFormId ?: "(none)")
        }

        Spacer(20)
        SectionHeader("Localization")
        Spacer(8)
        TesterCard {
            InfoRow("Locale", appliedLocale ?: "(device default)")
            SettingsDivider()
            InfoRow("Country", appliedCountry ?: "(unset)")
            Spacer(12)
            SecondaryPillButton(text = "Set Locale…", onClick = { showLocaleDialog = true })
            Spacer(10)
            SecondaryPillButton(text = "Set Country…", onClick = { showCountryDialog = true })
        }

        Spacer(20)
        QuietTextButton(text = "Change API key & setup", onClick = onChangeSetup, color = cs.error)
        Spacer(24)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** Full-screen centered notice with an ink glyph circle — Billing / RouteNotFound destinations. */
@Composable
private fun RouteNotice(
    glyph: String,
    title: String,
    subtitle: String,
    buttonText: String,
    onButton: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(72.dp).clip(CircleShape).background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = cs.ink)
        }
        Spacer(16)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = cs.onBackground)
        Spacer(8)
        Text(subtitle, fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(24)
        PrimaryPillButton(text = buttonText, onClick = onButton)
    }
}

@Composable
private fun BillingScreen(route: String, onBackToHome: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("Billing") }
    RouteNotice(
        glyph = "$",
        title = "Billing",
        subtitle = "Reached via CTA app_navigate route: \"$route\"",
        buttonText = "Back to home",
        onButton = onBackToHome,
    )
}

@Composable
private fun RouteNotFoundScreen(route: String, onGoBack: () -> Unit) {
    LaunchedEffect(Unit) { Encatch.trackScreen("RouteNotFound") }
    RouteNotice(
        glyph = "?",
        title = "Route not found",
        subtitle = "The CTA requested an unmapped route: \"$route\"",
        buttonText = "Go back",
        onButton = onGoBack,
    )
}

@Composable
internal fun Spacer(dp: Int = 16) {
    LayoutSpacer(Modifier.height(dp.dp))
}
