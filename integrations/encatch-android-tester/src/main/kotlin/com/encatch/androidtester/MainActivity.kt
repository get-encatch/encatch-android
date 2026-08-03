package com.encatch.androidtester

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Single-activity tester app, modeled on the encatch-expo-tester RN app: a runtime Setup screen
 * so one APK works for any tester/environment, then Login/Home/Events/Inline/Settings screens
 * exercising the SDK's public API, plus CTA-driven in-app navigation (Billing / route-not-found).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = TesterPrefs(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TesterApp(prefs = prefs, scope = lifecycleScope)
                }
            }
        }
    }
}

@Composable
private fun TesterApp(prefs: TesterPrefs, scope: CoroutineScope) {
    var screen by remember {
        mutableStateOf<Screen>(if (prefs.isSetupComplete) Screen.Login else Screen.Setup)
    }
    var lastEvent by remember { mutableStateOf("No events yet") }
    var interceptedFormId by remember { mutableStateOf<String?>(null) }
    var interceptorResume by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

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

    interceptedFormId?.let { formId ->
        InterceptorDialog(
            formId = formId,
            onResult = { allow ->
                interceptorResume?.invoke(allow)
                interceptedFormId = null
                interceptorResume = null
            },
        )
    }

    when (val current = screen) {
        Screen.Setup -> SetupScreen(
            onContinue = { apiKey, formId, baseUrl, webHost, interceptorFormId ->
                prefs.apiKey = apiKey
                prefs.formId = formId
                prefs.apiBaseUrl = baseUrl.ifBlank { null }
                prefs.webHost = webHost.ifBlank { null }
                prefs.interceptorFormId = interceptorFormId.ifBlank { null }
                scope.launch {
                    Encatch.init(
                        apiKey,
                        EncatchConfig(
                            apiBaseUrl = prefs.apiBaseUrl ?: com.encatch.core.DEFAULT_API_BASE_URL,
                            webHost = prefs.webHost ?: com.encatch.core.DEFAULT_WEB_HOST,
                            debugMode = true,
                            // Demonstrates the blocked-form / native-replacement pattern: any
                            // showForm() call for the configured interceptor form id is held
                            // here until the tester answers the InterceptorDialog above.
                            onBeforeShowForm = { payload ->
                                if (payload.formId == prefs.interceptorFormId) {
                                    val deferred = CompletableDeferred<Boolean>()
                                    interceptedFormId = payload.formId
                                    interceptorResume = { deferred.complete(it) }
                                    deferred.await()
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
            initialUserName = prefs.userName.orEmpty(),
            onLogIn = { userName ->
                prefs.userName = userName
                scope.launch {
                    Encatch.identifyUser(userName)
                    screen = Screen.Home
                }
            },
        )

        Screen.Home -> HomeScreen(
            lastEvent = lastEvent,
            interceptorFormId = prefs.interceptorFormId,
            onShowModalForm = { scope.launch { Encatch.showForm(prefs.formId.orEmpty()) } },
            onShowInterceptorForm = { id -> scope.launch { Encatch.showForm(id) } },
            onNavigate = { screen = it },
        )

        Screen.Events -> EventsScreen(
            onTrack = { name -> scope.launch { Encatch.trackEvent(name) } },
            onBack = { screen = Screen.Home },
        )

        Screen.Inline -> InlineScreen(
            exactFormId = prefs.formId.orEmpty(),
            onShowExact = { scope.launch { Encatch.showForm(prefs.formId.orEmpty()) } },
            onShowWildcard = { id -> scope.launch { Encatch.showForm(id) } },
            onBack = { screen = Screen.Home },
        )

        Screen.Settings -> SettingsScreen(
            prefs = prefs,
            onLogOut = {
                scope.launch {
                    Encatch.resetUser()
                    screen = Screen.Login
                }
            },
            onClearSetup = {
                scope.launch {
                    Encatch.clearAll()
                    prefs.clear()
                    screen = Screen.Setup
                }
            },
            onBack = { screen = Screen.Home },
        )

        is Screen.Billing -> BillingScreen(route = current.route, onBackToHome = { screen = Screen.Home })

        is Screen.RouteNotFound -> RouteNotFoundScreen(route = current.route, onGoBack = { screen = Screen.Home })
    }
}
