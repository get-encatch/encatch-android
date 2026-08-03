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
    data object Home : Screen()
    data object Events : Screen()
    data object Inline : Screen()
    data object Settings : Screen()
    data class Billing(val route: String) : Screen()
    data class RouteNotFound(val route: String) : Screen()
}

/**
 * Plain Android Views (no Compose) — deliberately, to prove `:kmp-sdk` needs nothing beyond
 * `commonMain` calls into `TesterController`. Screens are built programmatically and swapped in a
 * single root `FrameLayout` rather than via Fragments/Activities, mirroring
 * `encatch-android-tester`'s Compose-based screen-state approach but without a UI framework
 * dependency.
 */
class MainActivity : Activity() {
    private lateinit var prefs: TesterPrefs
    private lateinit var root: FrameLayout
    private val scope = CoroutineScope(Dispatchers.Main)
    private var screen: Screen = Screen.Setup
    private var lastEvent = "No events yet"
    private var unsubscribe: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TesterPrefs(this)
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
                    screen == Screen.Home -> render(Screen.Home)
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
            Screen.Home -> buildHomeScreen()
            Screen.Events -> buildEventsScreen()
            Screen.Inline -> buildInlineScreen()
            Screen.Settings -> buildSettingsScreen()
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
        val apiKeyInput = input("API key *")
        val formIdInput = input("Default form id (feedback config) *")
        val baseUrlInput = input("API base URL (optional)")
        val webHostInput = input("Web host (optional)")

        val col = column()
        col.addView(heading("Encatch KMP Tester — Setup"))
        col.addView(body("Enter your own API key and default form id. Saved locally on this device — this same APK works for any tester or environment."))
        col.addView(apiKeyInput)
        col.addView(formIdInput)
        col.addView(baseUrlInput)
        col.addView(webHostInput)
        col.addView(
            actionButton("Save & continue") {
                val apiKey = apiKeyInput.text.toString().trim()
                val formId = formIdInput.text.toString().trim()
                if (apiKey.isEmpty() || formId.isEmpty()) return@actionButton
                prefs.apiKey = apiKey
                prefs.formId = formId
                prefs.apiBaseUrl = baseUrlInput.text.toString().trim().ifEmpty { null }
                prefs.webHost = webHostInput.text.toString().trim().ifEmpty { null }
                scope.launch {
                    runCatching { TesterController.initSdk(apiKey, prefs.apiBaseUrl, prefs.webHost) }
                    render(Screen.Login)
                }
            },
        )
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildLoginScreen(): View {
        val usernameInput = input("Username").apply { setText(prefs.userName ?: "") }
        val col = column()
        col.addView(heading("Log in"))
        col.addView(body("Mock login — calls TesterController.identify(userName)."))
        col.addView(usernameInput)
        col.addView(
            actionButton("Log in") {
                val userName = usernameInput.text.toString().trim()
                if (userName.isEmpty()) return@actionButton
                prefs.userName = userName
                scope.launch {
                    runCatching { TesterController.identify(userName) }
                    render(Screen.Home)
                }
            },
        )
        return col
    }

    private fun buildHomeScreen(): View {
        scope.launch {
            runCatching {
                TesterController.trackScreen("Home")
                TesterController.trackEvent("home_viewed")
            }
        }
        val col = column()
        col.addView(heading("Home"))
        col.addView(body("Last event: $lastEvent"))
        col.addView(
            actionButton("Show form (modal)") {
                scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } }
            },
        )
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        nav.addView(actionButton("Events") { render(Screen.Events) })
        nav.addView(actionButton("Inline") { render(Screen.Inline) })
        nav.addView(actionButton("Settings") { render(Screen.Settings) })
        col.addView(nav)
        return col
    }

    private fun buildEventsScreen(): View {
        scope.launch { runCatching { TesterController.trackScreen("Events") } }
        val col = column()
        col.addView(heading("Events"))
        listOf("button_clicked", "feature_used", "purchase_started", "survey_viewed").forEach { name ->
            col.addView(actionButton(name) { scope.launch { runCatching { TesterController.trackEvent(name) } } })
        }
        col.addView(actionButton("Back") { render(Screen.Home) })
        return col
    }

    private fun buildInlineScreen(): View {
        scope.launch { runCatching { TesterController.trackScreen("Inline") } }
        val col = column()
        col.addView(heading("Inline forms"))

        col.addView(body("Exact (claims \"${prefs.formId}\")"))
        val exactForm = EncatchInlineFormView(this).apply { formId = prefs.formId }
        col.addView(exactForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 640))
        col.addView(
            actionButton("Show exact inline form") {
                scope.launch { runCatching { TesterController.showForm(prefs.formId.orEmpty()) } }
            },
        )

        col.addView(body("Wildcard (catches any form id not exactly claimed elsewhere)"))
        val wildcardForm = EncatchInlineFormView(this).apply { formId = null }
        col.addView(wildcardForm, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 640))
        val wildcardInput = input("Form id")
        col.addView(wildcardInput)
        col.addView(
            actionButton("Show in wildcard slot") {
                val id = wildcardInput.text.toString().trim()
                if (id.isEmpty()) return@actionButton
                scope.launch { runCatching { TesterController.showForm(id) } }
            },
        )

        col.addView(actionButton("Back") { render(Screen.Home) })
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildSettingsScreen(): View {
        scope.launch { runCatching { TesterController.trackScreen("Settings") } }
        val col = column()
        col.addView(heading("Settings"))
        col.addView(body("Form id: ${prefs.formId}"))
        col.addView(body("API base URL: ${prefs.apiBaseUrl ?: "(default)"}"))
        col.addView(body("Web host: ${prefs.webHost ?: "(default)"}"))
        col.addView(
            actionButton("Log out") {
                scope.launch {
                    runCatching { TesterController.resetUser() }
                    render(Screen.Login)
                }
            },
        )
        col.addView(
            actionButton("Clear saved setup") {
                scope.launch {
                    runCatching { TesterController.clearAll() }
                    prefs.clear()
                    render(Screen.Setup)
                }
            },
        )
        col.addView(actionButton("Back") { render(Screen.Home) })
        return col
    }

    private fun buildBillingScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("Billing") } }
        val col = column()
        col.addView(heading("Billing"))
        col.addView(body("Reached via CTA app_navigate route: \"$route\""))
        col.addView(actionButton("Back to home") { render(Screen.Home) })
        return col
    }

    private fun buildRouteNotFoundScreen(route: String): View {
        scope.launch { runCatching { TesterController.trackScreen("RouteNotFound") } }
        val col = column()
        col.addView(heading("Route not found"))
        col.addView(body("The CTA requested an unmapped route: \"$route\""))
        col.addView(actionButton("Go back") { render(Screen.Home) })
        return col
    }
}
