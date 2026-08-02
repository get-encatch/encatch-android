package com.encatch.composesample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.encatch.core.Encatch
import com.encatch.core.EncatchConfig
import kotlinx.coroutines.launch

/**
 * The Compose Multiplatform "host app" variant: a customer building with Compose Multiplatform
 * (Android + iOS from one commonMain UI) embedding Encatch's existing native UI components via
 * interop — [EncatchInlineFormHost] resolves to `AndroidView` on Android and `UIKitView` on iOS,
 * wrapping the same platform-native views/view-controllers used by the plain-native samples.
 * No WebView reimplementation, no shared UI code duplicating `:android`/`swift`.
 */
@Composable
fun ComposeSampleApp(mockServerBaseUrl: String?) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ComposeSampleScreen(mockServerBaseUrl)
        }
    }
}

@Composable
private fun ComposeSampleScreen(mockServerBaseUrl: String?) {
    var status by remember { mutableStateOf("Not initialized") }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Encatch Compose Multiplatform Sample", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        Button(onClick = {
            scope.launch {
                val config = if (mockServerBaseUrl != null) {
                    EncatchConfig(apiBaseUrl = mockServerBaseUrl, webHost = mockServerBaseUrl, debugMode = true)
                } else {
                    EncatchConfig(debugMode = true)
                }
                Encatch.init("YOUR_API_KEY", config)
                status = "Initialized: ${Encatch.isInitialized}"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Init SDK")
        }

        Button(onClick = {
            scope.launch {
                Encatch.showForm("cmp-modal-form-id")
                status = "showForm(\"cmp-modal-form-id\") called"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Show modal form")
        }

        Text("Inline form slot:", style = MaterialTheme.typography.titleMedium)

        EncatchInlineFormHost(
            formId = "cmp-inline-form-id",
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )

        Button(onClick = {
            scope.launch {
                Encatch.showForm("cmp-inline-form-id")
                status = "showForm(\"cmp-inline-form-id\") called"
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Show inline form")
        }
    }
}

/**
 * Renders the SDK's native inline-form view for the current platform via Compose interop.
 * Android: `AndroidView` wrapping `:android`'s `EncatchInlineFormView` directly.
 * iOS: `UIKitView` wrapping `:ios-native-form-ui`'s `EncatchNativeInlineFormView` — a from-scratch
 * Kotlin/Native WebKit port, since Kotlin/Native can't cinterop against a Swift Package directly
 * and linking `swift/`'s XCFramework alongside this module's own would duplicate `:core`'s
 * singletons (see `EncatchNativeFormHost`'s doc comment).
 */
@Composable
expect fun EncatchInlineFormHost(formId: String, modifier: Modifier = Modifier)
