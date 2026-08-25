package com.encatch.kmptester

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.encatch.android.EncatchInlineFormView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private sealed class Screen {
    data object Setup : Screen()
    data object Login : Screen()
    data class EditProfile(val username: String) : Screen()
    data object Main : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}

private enum class TesterTab(val label: String) {
    HOME("Home"), EVENTS("Events"), LOGS("Logs"), SETTINGS("Settings"), INLINE_ANY("Inline (Any)"), INLINE_EXACT("Inline (Exact)")
}

/** One captured SDK HTTP call, flattened by TesterController.setOnNetworkLog. */
private data class NetworkLogRow(val status: Int, val name: String, val durationMs: Long, val fullText: String)

/**
 * Plain Android Views (no Compose) — deliberately, to prove `:kmp-sdk` needs nothing beyond
 * `commonMain` calls into `TesterController`. Screens are built programmatically and swapped in a
 * single root `FrameLayout` rather than via Fragments/Activities, mirroring
 * `encatch-android-tester`'s Compose-based screen-state approach but without a UI framework
 * dependency. Modeled on the richer `encatch-flutter-tester` reference app: saved test users,
 * environment presets, bottom-tab navigation, header theme cycling, and an interceptor carousel
 * that hands off to a fully custom native form renderer (`NativeForm.kt`). Visual styling lives
 * in `Theme.kt` and mirrors the Uber-style design system of `encatch-ios-tester`.
 */
class MainActivity : Activity() {
    private lateinit var prefs: TesterPrefs
    private lateinit var usersStore: TestUsersStore
    private lateinit var root: FrameLayout
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screen: Screen = Screen.Setup
    private var tab: TesterTab = TesterTab.HOME
    private var lastEvent = "No events yet"
    private var appliedLocale: String? = null
    private var appliedCountry: String? = null
    private var selectedUsername: String? = null
    private var currentTheme = "SYSTEM"
    private val blockedForms = mutableListOf<BlockedFormItem>()
    private val networkLogs = mutableListOf<NetworkLogRow>()
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TesterPrefs(this)
        usersStore = TestUsersStore(this)
        selectedUsername = prefs.userName
        root = FrameLayout(this)
        root.setBackgroundColor(surface())
        setContentView(root)
        applySystemBarStyle()

        unsubscribe = TesterController.onEvent { eventWireValue, formId, action, route ->
            runOnUiThread {
                lastEvent = "$eventWireValue (formId=$formId)"
                when {
                    action == "app_navigate" && (route == "billing" || route == "billing/upgrade") ->
                        render(Screen.Billing(route ?: "billing"))
                    action == "app_navigate" ->
                        render(Screen.RouteNotFound(route ?: "(none)"))
                    screen == Screen.Main && tab == TesterTab.HOME -> render(Screen.Main)
                }
            }
        }

        TesterController.setOnNetworkLog { status, endpointName, durationMs, fullText ->
            runOnUiThread {
                networkLogs.add(0, NetworkLogRow(status, endpointName, durationMs, fullText))
                if (networkLogs.size > 200) networkLogs.removeAt(networkLogs.size - 1)
                if (screen == Screen.Main && tab == TesterTab.LOGS) render(Screen.Main)
            }
        }

        // Auto-init on relaunch: with setup already complete the app skips the Setup screen, so
        // init must happen here instead of Setup's "Save & continue" (identifyUser/showForm
        // silently no-op un-initialized). Parity with encatch-android-tester/encatch-ios-tester.
        if (prefs.isSetupComplete) scope.launch { runCatching { initializeSdk() } }

        render(if (prefs.isSetupComplete) Screen.Login else Screen.Setup)
    }

    /** Shared by Setup's "Save & continue" and the relaunch auto-init above. */
    private suspend fun initializeSdk() {
        TesterController.initSdk(
            prefs.apiKey.orEmpty(),
            prefs.apiBaseUrl ?: prefs.environment.apiBaseUrl,
            prefs.webHost ?: prefs.environment.webHost,
            prefs.interceptorFormId,
            onIntercept = { blockedFormId, formConfigJson, completion ->
                runOnUiThread {
                    // formConfigJson only carries questionnaireFields (see
                    // ShowFormInterceptorPayload.formConfigJson's doc comment in kmp-sdk), not
                    // a form title — fall back to the raw form id.
                    blockedForms += BlockedFormItem(blockedFormId, blockedFormId, formConfigJson)
                    if (screen == Screen.Main) render(Screen.Main)
                }
                completion(false)
            },
        )
    }

    /** Match the monochrome surface: white/black status bar with legible icons in both modes. */
    @Suppress("DEPRECATION")
    private fun applySystemBarStyle() {
        window.statusBarColor = surface()
        window.navigationBarColor = surface()
        if (!isNightMode()) {
            window.decorView.systemUiVisibility =
                window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    override fun onDestroy() {
        unsubscribe?.invoke()
        super.onDestroy()
    }

    private fun render(next: Screen) {
        screen = next
        root.removeAllViews()
        val view = when (next) {
            Screen.Setup -> buildSetupScreen()
            Screen.Login -> buildLoginScreen()
            is Screen.EditProfile -> buildEditProfileScreen(next.username)
            Screen.Main -> buildMainScreen()
            is Screen.Billing -> buildBillingScreen(next.route)
            is Screen.RouteNotFound -> buildRouteNotFoundScreen(next.route)
        }
        root.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface())
        setPadding(dp(20f), dp(40f), dp(20f), dp(24f))
    }

    /** Content column for Main-screen tabs — the header already provides the top inset. */
    private fun tabColumn(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(surface())
        setPadding(dp(20f), dp(8f), dp(20f), dp(24f))
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink())
        setPadding(0, 0, 0, dp(8f))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(secondaryText())
        setPadding(0, 0, 0, dp(8f))
    }

    private fun input(hintText: String): EditText = filledField(hintText)

    /** Horizontal, scrollable row of compact chips — Uber's preset idiom. */
    private fun chipRow(build: (LinearLayout) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        build(row)
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun buildSetupScreen(): View {
        var environment = prefs.environment
        // Prefilled (still editable) from the developer-local dev-tester-defaults.properties.
        val apiKeyInput = input("API key *").apply { setText(BuildConfig.DEV_DEFAULT_API_KEY) }
        val formIdInput = input("Default form id (feedback config) *").apply { setText(BuildConfig.DEV_DEFAULT_FORM_ID) }
        val interceptorFormIdInput = input("Interceptor test form id (optional)")

        val col = column()
        col.addView(heading("Encatch KMP Tester"))
        col.addView(body("Enter your own API key and default form id. Saved locally on this device — this same APK works for any tester or environment."))

        col.addView(sectionHeader("Environment"))
        val envLabel = body("${environment.apiBaseUrl} · ${environment.webHost}").apply { textSize = 12f }
        lateinit var refreshEnvChips: () -> Unit
        val envRowView = chipRow { row ->
            refreshEnvChips = {
                row.removeAllViews()
                TesterEnvironment.entries.forEach { env ->
                    row.addView(chipButton(env.label, selected = env == environment) {
                        environment = env
                        envLabel.text = "${env.apiBaseUrl} · ${env.webHost}"
                        refreshEnvChips()
                    })
                }
            }
            refreshEnvChips()
        }
        col.addView(envRowView)
        col.addView(envLabel)

        col.addView(fieldLabel("API key *"))
        col.addView(apiKeyInput.apply { hint = null })
        col.addView(fieldLabel("Default form id (feedback config) *"))
        col.addView(formIdInput.apply { hint = null })
        col.addView(fieldLabel("Interceptor test form id (optional)"))
        col.addView(interceptorFormIdInput.apply { hint = null })
        col.addView(
            primaryButton("Save & continue") {
                val apiKey = apiKeyInput.text.toString().trim()
                val formId = formIdInput.text.toString().trim()
                if (apiKey.isEmpty() || formId.isEmpty()) return@primaryButton
                prefs.environment = environment
                prefs.apiKey = apiKey
                prefs.formId = formId
                prefs.apiBaseUrl = environment.apiBaseUrl
                prefs.webHost = environment.webHost
                prefs.interceptorFormId = interceptorFormIdInput.text.toString().trim().ifEmpty { null }
                scope.launch {
                    runCatching { initializeSdk() }
                    render(Screen.Login)
                }
            },
        )
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildLoginScreen(): View {
        val col = column()
        col.addView(heading("Log in"))
        col.addView(body("Mock login — calls TesterController.identify(userName). Saved users are local to this tester, independent of the SDK."))

        col.addView(sectionHeader("Saved users"))
        val users = usersStore.list()
        if (users.isEmpty()) col.addView(body("No saved users yet."))
        users.forEach { user ->
            val selected = selectedUsername == user.username
            val userView = card()
            userView.addView(TextView(this).apply {
                text = user.username
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ink())
            })
            if (user.displayName.isNotBlank() || user.email.isNotBlank()) {
                userView.addView(TextView(this).apply {
                    text = listOf(user.displayName, user.email).filter { it.isNotBlank() }.joinToString(" · ")
                    textSize = 13f
                    setTextColor(secondaryText())
                })
            }
            if (selected) {
                userView.addView(TextView(this).apply {
                    text = "Selected"
                    textSize = 13f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(TesterTheme.GREEN)
                    setPadding(0, dp(2f), 0, 0)
                })
            }
            userView.setOnClickListener { selectedUsername = user.username; render(Screen.Login) }
            col.addView(userView)
        }

        val newUsernameInput = input("Username")
        val newEmailInput = input("Email")
        val newDisplayNameInput = input("Display name")
        val newUserForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(fieldLabel("Username"))
            addView(newUsernameInput.apply { hint = null })
            addView(fieldLabel("Email"))
            addView(newEmailInput.apply { hint = null })
            addView(fieldLabel("Display name"))
            addView(newDisplayNameInput.apply { hint = null })
        }
        col.addView(
            secondaryButton("+ New user") { newUserForm.visibility = View.VISIBLE },
        )
        col.addView(newUserForm)
        col.addView(
            secondaryButton("Save user") {
                val username = newUsernameInput.text.toString().trim()
                if (username.isEmpty()) return@secondaryButton
                val user = TestUser(username, newEmailInput.text.toString().trim(), newDisplayNameInput.text.toString().trim())
                usersStore.add(user)
                selectedUsername = username
                render(Screen.Login)
            },
        )

        selectedUsername?.let { username ->
            col.addView(quietButton("Edit profile before sign in") { render(Screen.EditProfile(username)) })
        }

        col.addView(
            primaryButton("Identify user") {
                val username = selectedUsername ?: return@primaryButton
                val user = usersStore.list().find { it.username == username }
                scope.launch {
                    runCatching { TesterController.identify(username, user?.email, user?.displayName) }
                    prefs.userName = username
                    tab = TesterTab.HOME
                    render(Screen.Main)
                }
            },
        )
        col.addView(
            quietButton("Change API key & setup") {
                scope.launch {
                    runCatching { TesterController.resetUser() }
                    prefs.clear()
                    render(Screen.Setup)
                }
            },
        )
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildEditProfileScreen(username: String): View {
        val existing = usersStore.list().find { it.username == username }
        val emailInput = input("Email").apply { setText(existing?.email ?: ""); hint = null }
        val displayNameInput = input("Display name").apply { setText(existing?.displayName ?: ""); hint = null }

        val col = column()
        col.addView(heading("Edit profile"))
        col.addView(body("Username: $username"))
        col.addView(fieldLabel("Email"))
        col.addView(emailInput)
        col.addView(fieldLabel("Display name"))
        col.addView(displayNameInput)
        col.addView(
            primaryButton("Save & identify") {
                val updated = TestUser(username, emailInput.text.toString().trim(), displayNameInput.text.toString().trim())
                usersStore.update(updated)
                if (selectedUsername == username) {
                    scope.launch { runCatching { TesterController.identify(username, updated.email, updated.displayName) } }
                }
                render(Screen.Login)
            },
        )
        col.addView(quietButton("Back") { render(Screen.Login) })
        return col
    }

    private fun buildMainScreen(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(surface()) }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(40f), dp(20f), dp(12f))
        }
        header.addView(TextView(this).apply {
            text = tab.label
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ink())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(chipButton(currentTheme) {
            currentTheme = TesterController.cycleTheme()
            render(Screen.Main)
        })
        header.addView(chipButton("Logout") {
            scope.launch {
                runCatching { TesterController.resetUser() }
                render(Screen.Login)
            }
        })
        outer.addView(header)

        val content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        content.addView(
            when (tab) {
                TesterTab.HOME -> buildHomeTab()
                TesterTab.EVENTS -> buildEventsTab()
                TesterTab.LOGS -> buildLogsTab()
                TesterTab.SETTINGS -> buildSettingsTab()
                TesterTab.INLINE_ANY -> buildInlineAnyTab()
                TesterTab.INLINE_EXACT -> buildInlineExactTab()
            },
        )
        outer.addView(content)

        val carouselContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        renderInterceptorCarousel(
            carouselContainer,
            blockedForms,
            onOpen = { item ->
                root.addView(
                    buildNativeFormModal(item, scope) {
                        blockedForms.removeAll { it.formId == item.formId }
                        render(Screen.Main)
                    },
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
                )
            },
            onDismiss = { formId -> blockedForms.removeAll { it.formId == formId }; render(Screen.Main) },
        )
        outer.addView(carouselContainer)

        // Monochrome bottom navigation: selected = ink bold, unselected = gray.
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(surface())
            setPadding(dp(4f), dp(6f), dp(4f), dp(10f))
        }
        TesterTab.entries.forEach { t ->
            val selected = t == tab
            nav.addView(
                TextView(this).apply {
                    text = t.label
                    textSize = 10f
                    gravity = Gravity.CENTER
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(if (selected) ink() else secondaryText())
                    setPadding(0, dp(8f), 0, dp(8f))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { tab = t; render(Screen.Main) }
                },
            )
        }
        outer.addView(nav)

        return outer
    }

    private fun buildHomeTab(): View {
        scope.launch {
            runCatching {
                TesterController.trackScreen("Home")
                TesterController.trackEvent("home_viewed")
            }
        }
        val col = tabColumn()
        prefs.userName?.let { userName ->
            val row = card()
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.addView(TextView(this).apply {
                text = "Signed in as $userName"
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ink())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(chipButton("Edit profile") { render(Screen.EditProfile(userName)) }.apply {
                background = pillBackground(surface())
            })
            col.addView(row)
        }
        col.addView(body("Last event: $lastEvent"))
        col.addView(primaryButton("Show Form") { scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } } })
        col.addView(
            secondaryButton("Show Form (prefilled)") {
                scope.launch { runCatching { TesterController.showPrefilledForm(prefs.formId.orEmpty(), "prefill-question", "hello") } }
            },
        )
        val interceptorFormId = prefs.interceptorFormId
        if (!interceptorFormId.isNullOrBlank()) {
            col.addView(
                secondaryButton("Show Form (interceptor test)") {
                    scope.launch { runCatching { TesterController.showForm(interceptorFormId) } }
                },
            )
        }
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildEventsTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("Events") } }
        val col = tabColumn()
        col.addView(sectionHeader("trackEvent presets"))
        col.addView(chipRow { row ->
            listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed").forEach { name ->
                row.addView(chipButton(name) { scope.launch { runCatching { TesterController.trackEvent(name) } } })
            }
        })
        val customEvent = input("Custom event").apply { setText("test_event"); hint = null }
        col.addView(fieldLabel("Custom event"))
        col.addView(customEvent)
        col.addView(primaryButton("Fire") { scope.launch { runCatching { TesterController.trackEvent(customEvent.text.toString().trim()) } } })

        col.addView(sectionHeader("trackScreen presets"))
        col.addView(chipRow { row ->
            listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test").forEach { path ->
                row.addView(chipButton(path) { scope.launch { runCatching { TesterController.trackScreen(path) } } })
            }
        })
        val customScreen = input("Custom screen").apply { setText("/dashboard/encatch-test"); hint = null }
        col.addView(fieldLabel("Custom screen"))
        col.addView(customScreen)
        col.addView(primaryButton("Track") { scope.launch { runCatching { TesterController.trackScreen(customScreen.text.toString().trim()) } } })
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildLogsTab(): View {
        val col = tabColumn()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(body("${networkLogs.size} requests · newest first").apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(chipButton("Copy all") {
            copyToClipboard(networkLogs.joinToString("\n\n============\n\n") { it.fullText })
        })
        header.addView(chipButton("Clear") { networkLogs.clear(); render(Screen.Main) })
        col.addView(header)

        if (networkLogs.isEmpty()) {
            col.addView(body("No SDK requests yet"))
        }
        networkLogs.forEach { row ->
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6f), 0, dp(6f))
                setOnClickListener { showLogDetail(row) }
            }
            rowView.addView(TextView(this).apply {
                text = if (row.status == 0) "ERR" else row.status.toString()
                setTextColor(if (row.status in 200..299) TesterTheme.GREEN else TesterTheme.RED)
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setPadding(0, 0, dp(12f), 0)
            })
            rowView.addView(body("${row.name} · ${row.durationMs}ms").apply {
                setPadding(0, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            rowView.addView(chipButton("Copy") { copyToClipboard(row.fullText) })
            col.addView(rowView)
        }
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun showLogDetail(row: NetworkLogRow) {
        val content = ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                text = row.fullText
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
                setTextIsSelectable(true)
            })
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(row.name)
            .setView(content)
            .setPositiveButton("Copy") { _, _ -> copyToClipboard(row.fullText) }
            .setNegativeButton("Close", null)
            .show()
    }

    /**
     * Modal prompting for a locale/country code to switch the SDK to. Blank input is ignored;
     * the applied value is echoed back in the settings info rows (the SDK setters are silent —
     * they only affect the NEXT showForm).
     */
    private fun promptLocaleInput(title: String, hintText: String, initial: String?, onApply: (String) -> Unit) {
        val field = filledField(hintText).apply { setText(initial ?: "") }
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
            addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(body("Applies to the next form shown — an already-open form keeps its language."))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setPositiveButton("Apply") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) onApply(value)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Encatch logs", text))
    }

    private fun buildInlineExactTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("InlineExact") } }
        val col = tabColumn()
        col.addView(body("Claims \"${prefs.formId}\" — only renders inline when that exact form id is shown."))
        col.addView(primaryButton("Show Exact Form (renders inline below)") {
            scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } }
        })
        val exactForm = EncatchInlineFormView(this).apply { formId = prefs.formId }
        // No fixed height — the SDK view drives its own layoutParams height (skeleton
        // placeholder, then live form:resize values).
        col.addView(exactForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildInlineAnyTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("InlineAny") } }
        val col = tabColumn()
        col.addView(body("Catches any form id not exactly claimed elsewhere."))
        val wildcardInput = input("Form id").apply { hint = null }
        col.addView(fieldLabel("Form id"))
        col.addView(wildcardInput)
        col.addView(
            primaryButton("Show Form (renders inline below)") {
                val id = wildcardInput.text.toString().trim()
                if (id.isEmpty()) return@primaryButton
                scope.launch { runCatching { TesterController.showForm(id) } }
            },
        )
        col.addView(secondaryButton("Trigger unmatched form → modal fallback") {
            scope.launch { runCatching { TesterController.showForm("modal-fallback-demo") } }
        })
        val wildcardForm = EncatchInlineFormView(this).apply { formId = null }
        col.addView(wildcardForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildSettingsTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("Settings") } }
        val col = tabColumn()
        val info = card()
        fun infoRow(label: String, value: String) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2f), 0, dp(2f)) }
            row.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(secondaryText())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = value
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(ink())
                gravity = Gravity.END
            })
            info.addView(row)
        }
        infoRow("SDK variant", "KMP (com.encatch:kmp-sdk, Android)")
        infoRow("Environment", prefs.environment.label)
        infoRow("Form id", prefs.formId ?: "(none)")
        infoRow("API base URL", prefs.apiBaseUrl ?: "(default)")
        infoRow("Web host", prefs.webHost ?: "(default)")
        infoRow("Interceptor form id", prefs.interceptorFormId ?: "(none)")
        infoRow("Locale", appliedLocale ?: "(device default)")
        infoRow("Country", appliedCountry ?: "(unset)")
        col.addView(info)
        col.addView(chipRow { row ->
            row.addView(chipButton("Set Locale…") {
                promptLocaleInput("Set Locale", "e.g. fr-FR, hi-IN", appliedLocale) {
                    TesterController.setLocale(it)
                    appliedLocale = it
                    render(Screen.Main)
                }
            })
            row.addView(chipButton("Set Country…") {
                promptLocaleInput("Set Country", "e.g. FR, IN", appliedCountry) {
                    TesterController.setCountry(it)
                    appliedCountry = it
                    render(Screen.Main)
                }
            })
        })
        col.addView(
            quietButton("Change API key & setup") {
                scope.launch {
                    runCatching { TesterController.resetUser() }
                    prefs.clear()
                    render(Screen.Setup)
                }
            },
        )
        return ScrollView(this).apply { setBackgroundColor(surface()); addView(col) }
    }

    private fun buildBillingScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("Billing") } }
        val col = column()
        col.addView(heading("Billing"))
        col.addView(body("Reached via CTA app_navigate route: \"$route\""))
        col.addView(primaryButton("Back to home") { render(Screen.Main) })
        return col
    }

    private fun buildRouteNotFoundScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("RouteNotFound") } }
        val col = column()
        col.addView(heading("Route not found"))
        col.addView(body("The CTA requested an unmapped route: \"$route\""))
        col.addView(primaryButton("Go back") { render(Screen.Main) })
        return col
    }
}
