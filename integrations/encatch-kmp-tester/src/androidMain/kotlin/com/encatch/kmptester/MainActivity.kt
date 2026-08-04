package com.encatch.kmptester

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
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
    HOME("Home"), EVENTS("Events"), SETTINGS("Settings"), INLINE_ANY("Inline (Any)"), INLINE_EXACT("Inline (Exact)")
}

/**
 * Plain Android Views (no Compose) — deliberately, to prove `:kmp-sdk` needs nothing beyond
 * `commonMain` calls into `TesterController`. Screens are built programmatically and swapped in a
 * single root `FrameLayout` rather than via Fragments/Activities, mirroring
 * `encatch-android-tester`'s Compose-based screen-state approach but without a UI framework
 * dependency. Modeled on the richer `encatch-flutter-tester` reference app: saved test users,
 * environment presets, bottom-tab navigation, header theme cycling, and an interceptor carousel
 * that hands off to a fully custom native form renderer (`NativeForm.kt`).
 */
class MainActivity : Activity() {
    private lateinit var prefs: TesterPrefs
    private lateinit var usersStore: TestUsersStore
    private lateinit var root: FrameLayout
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screen: Screen = Screen.Setup
    private var tab: TesterTab = TesterTab.HOME
    private var lastEvent = "No events yet"
    private var selectedUsername: String? = null
    private var currentTheme = "SYSTEM"
    private val blockedForms = mutableListOf<BlockedFormItem>()
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TesterPrefs(this)
        usersStore = TestUsersStore(this)
        selectedUsername = prefs.userName
        root = FrameLayout(this)
        setContentView(root)

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

        render(if (prefs.isSetupComplete) Screen.Login else Screen.Setup)
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
        setPadding(48, 120, 48, 48)
    }

    private fun heading(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 22f
        setPadding(0, 0, 0, 24)
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 0, 0, 16)
        setTextColor(Color.DKGRAY)
    }

    private fun input(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setPadding(24, 24, 24, 24)
    }

    private fun actionButton(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }

    private fun buildSetupScreen(): View {
        var environment = prefs.environment
        val apiKeyInput = input("API key *")
        val formIdInput = input("Default form id (feedback config) *")
        val interceptorFormIdInput = input("Interceptor test form id (optional)")

        val col = column()
        col.addView(heading("Encatch KMP Tester — Setup"))
        col.addView(body("Enter your own API key and default form id. Saved locally on this device — this same APK works for any tester or environment."))

        col.addView(body("Environment"))
        val envRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val envLabel = TextView(this).apply { text = "${environment.apiBaseUrl} · ${environment.webHost}"; textSize = 12f }
        TesterEnvironment.entries.forEach { env ->
            envRow.addView(actionButton(env.label) {
                environment = env
                envLabel.text = "${env.apiBaseUrl} · ${env.webHost}"
            })
        }
        col.addView(envRow)
        col.addView(envLabel)

        col.addView(apiKeyInput)
        col.addView(formIdInput)
        col.addView(interceptorFormIdInput)
        col.addView(
            actionButton("Save & continue") {
                val apiKey = apiKeyInput.text.toString().trim()
                val formId = formIdInput.text.toString().trim()
                if (apiKey.isEmpty() || formId.isEmpty()) return@actionButton
                prefs.environment = environment
                prefs.apiKey = apiKey
                prefs.formId = formId
                prefs.apiBaseUrl = environment.apiBaseUrl
                prefs.webHost = environment.webHost
                prefs.interceptorFormId = interceptorFormIdInput.text.toString().trim().ifEmpty { null }
                scope.launch {
                    runCatching {
                        TesterController.initSdk(
                            apiKey,
                            environment.apiBaseUrl,
                            environment.webHost,
                            prefs.interceptorFormId,
                            onIntercept = { blockedFormId, formConfigJson, completion ->
                                runOnUiThread {
                                    // formConfigJson only carries questionnaireFields (see
                                    // ShowFormInterceptorPayload.formConfigJson's doc comment in
                                    // kmp-sdk), not a form title — fall back to the raw form id.
                                    blockedForms += BlockedFormItem(blockedFormId, blockedFormId, formConfigJson)
                                    if (screen == Screen.Main) render(Screen.Main)
                                }
                                completion(false)
                            },
                        )
                    }
                    render(Screen.Login)
                }
            },
        )
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildLoginScreen(): View {
        val col = column()
        col.addView(heading("Log in"))
        col.addView(body("Mock login — calls TesterController.identify(userName). Saved users are local to this tester, independent of the SDK."))

        col.addView(body("Saved users"))
        val users = usersStore.list()
        if (users.isEmpty()) col.addView(body("No saved users yet."))
        users.forEach { user ->
            val userView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
            userView.addView(TextView(this).apply { text = user.username; textSize = 16f })
            if (user.displayName.isNotBlank() || user.email.isNotBlank()) {
                userView.addView(TextView(this).apply { text = listOf(user.displayName, user.email).filter { it.isNotBlank() }.joinToString(" · ") })
            }
            if (selectedUsername == user.username) userView.addView(TextView(this).apply { text = "Selected" })
            userView.setOnClickListener { selectedUsername = user.username; render(Screen.Login) }
            col.addView(userView)
        }

        val newUsernameInput = input("Username")
        val newEmailInput = input("Email")
        val newDisplayNameInput = input("Display name")
        val newUserForm = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(newUsernameInput)
            addView(newEmailInput)
            addView(newDisplayNameInput)
        }
        col.addView(
            actionButton("+ New user") { newUserForm.visibility = View.VISIBLE },
        )
        col.addView(newUserForm)
        col.addView(
            actionButton("Save user") {
                val username = newUsernameInput.text.toString().trim()
                if (username.isEmpty()) return@actionButton
                val user = TestUser(username, newEmailInput.text.toString().trim(), newDisplayNameInput.text.toString().trim())
                usersStore.add(user)
                selectedUsername = username
                render(Screen.Login)
            },
        )

        selectedUsername?.let { username ->
            col.addView(actionButton("Edit profile before sign in") { render(Screen.EditProfile(username)) })
        }

        col.addView(
            actionButton("Identify user") {
                val username = selectedUsername ?: return@actionButton
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
            actionButton("Change API key & setup") {
                scope.launch {
                    runCatching { TesterController.resetUser() }
                    prefs.clear()
                    render(Screen.Setup)
                }
            },
        )
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildEditProfileScreen(username: String): View {
        val existing = usersStore.list().find { it.username == username }
        val emailInput = input("Email").apply { setText(existing?.email ?: "") }
        val displayNameInput = input("Display name").apply { setText(existing?.displayName ?: "") }

        val col = column()
        col.addView(heading("Edit profile"))
        col.addView(body("Username: $username"))
        col.addView(emailInput)
        col.addView(displayNameInput)
        col.addView(
            actionButton("Save & identify") {
                val updated = TestUser(username, emailInput.text.toString().trim(), displayNameInput.text.toString().trim())
                usersStore.update(updated)
                if (selectedUsername == username) {
                    scope.launch { runCatching { TesterController.identify(username, updated.email, updated.displayName) } }
                }
                render(Screen.Login)
            },
        )
        col.addView(actionButton("Back") { render(Screen.Login) })
        return col
    }

    private fun buildMainScreen(): View {
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 96, 24, 24) }
        header.addView(TextView(this).apply { text = tab.label; textSize = 18f; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        header.addView(actionButton(currentTheme) {
            currentTheme = TesterController.cycleTheme()
            render(Screen.Main)
        })
        header.addView(actionButton("Logout") {
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

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        TesterTab.entries.forEach { t ->
            nav.addView(
                Button(this).apply {
                    text = t.label
                    textSize = 10f
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
        val col = column()
        prefs.userName?.let { userName ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply { text = "Signed in as $userName" })
            row.addView(actionButton("Edit profile") { render(Screen.EditProfile(userName)) })
            col.addView(row)
        }
        col.addView(body("Last event: $lastEvent"))
        col.addView(actionButton("Show Form") { scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } } })
        col.addView(
            actionButton("Show Form (prefilled)") {
                scope.launch { runCatching { TesterController.showPrefilledForm(prefs.formId.orEmpty(), "prefill-question", "hello") } }
            },
        )
        val interceptorFormId = prefs.interceptorFormId
        if (!interceptorFormId.isNullOrBlank()) {
            col.addView(
                actionButton("Show Form (interceptor test)") {
                    scope.launch { runCatching { TesterController.showForm(interceptorFormId) } }
                },
            )
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildEventsTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("Events") } }
        val col = column()
        col.addView(body("trackEvent presets"))
        listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed", "home_viewed").forEach { name ->
            col.addView(actionButton(name) { scope.launch { runCatching { TesterController.trackEvent(name) } } })
        }
        val customEvent = input("Custom event").apply { setText("test_event") }
        col.addView(customEvent)
        col.addView(actionButton("Fire") { scope.launch { runCatching { TesterController.trackEvent(customEvent.text.toString().trim()) } } })

        col.addView(body("trackScreen presets"))
        listOf("/home", "/dashboard", "/settings", "/dashboard/encatch-test").forEach { path ->
            col.addView(actionButton(path) { scope.launch { runCatching { TesterController.trackScreen(path) } } })
        }
        val customScreen = input("Custom screen").apply { setText("/dashboard/encatch-test") }
        col.addView(customScreen)
        col.addView(actionButton("Track") { scope.launch { runCatching { TesterController.trackScreen(customScreen.text.toString().trim()) } } })
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildInlineExactTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("InlineExact") } }
        val col = column()
        col.addView(body("Claims \"${prefs.formId}\" — only renders inline when that exact form id is shown."))
        col.addView(actionButton("Show Exact Form (renders inline below)") {
            scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } }
        })
        val exactForm = EncatchInlineFormView(this).apply { formId = prefs.formId }
        // No fixed height — the SDK view drives its own layoutParams height (skeleton
        // placeholder, then live form:resize values).
        col.addView(exactForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildInlineAnyTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("InlineAny") } }
        val col = column()
        col.addView(body("Catches any form id not exactly claimed elsewhere."))
        val wildcardInput = input("Form id")
        col.addView(wildcardInput)
        col.addView(
            actionButton("Show Form (renders inline below)") {
                val id = wildcardInput.text.toString().trim()
                if (id.isEmpty()) return@actionButton
                scope.launch { runCatching { TesterController.showForm(id) } }
            },
        )
        col.addView(actionButton("Trigger unmatched form → modal fallback") {
            scope.launch { runCatching { TesterController.showForm("modal-fallback-demo") } }
        })
        val wildcardForm = EncatchInlineFormView(this).apply { formId = null }
        col.addView(wildcardForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildSettingsTab(): View {
        scope.launch { runCatching { TesterController.trackScreen("Settings") } }
        val col = column()
        col.addView(body("Environment: ${prefs.environment.label}"))
        col.addView(body("Form id: ${prefs.formId}"))
        col.addView(body("API base URL: ${prefs.apiBaseUrl ?: "(default)"}"))
        col.addView(body("Web host: ${prefs.webHost ?: "(default)"}"))
        col.addView(body("Interceptor form id: ${prefs.interceptorFormId ?: "(none)"}"))
        col.addView(actionButton("Set Locale → fr-FR") { TesterController.setLocale("fr-FR") })
        col.addView(actionButton("Set Country → FR") { TesterController.setCountry("FR") })
        col.addView(
            actionButton("Change API key & setup") {
                scope.launch {
                    runCatching { TesterController.resetUser() }
                    prefs.clear()
                    render(Screen.Setup)
                }
            },
        )
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildBillingScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("Billing") } }
        val col = column()
        col.addView(heading("Billing"))
        col.addView(body("Reached via CTA app_navigate route: \"$route\""))
        col.addView(actionButton("Back to home") { render(Screen.Main) })
        return col
    }

    private fun buildRouteNotFoundScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("RouteNotFound") } }
        val col = column()
        col.addView(heading("Route not found"))
        col.addView(body("The CTA requested an unmapped route: \"$route\""))
        col.addView(actionButton("Go back") { render(Screen.Main) })
        return col
    }
}
