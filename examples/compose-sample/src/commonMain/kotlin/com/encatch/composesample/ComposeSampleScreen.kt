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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig
import com.encatch.sdk.compose.EncatchInlineForm
import kotlinx.coroutines.launch

/**
 * The Compose Multiplatform "host app" variant: a customer building with Compose Multiplatform
 * (Android + iOS from one commonMain UI) consuming the real `:compose-sdk`/`:kmp-sdk` libraries —
 * [EncatchInlineForm] (from `:compose-sdk`) resolves to `AndroidView` on Android and `UIKitView`
 * on iOS, wrapping the same platform-native views/view-controllers used by the plain-native
 * samples. No WebView reimplementation, no shared UI code duplicating `:android`/`swift`, and no
 * sample-owned bridging glue — this app is a thin consumer of the SDK modules, not their owner.
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
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("statusText"))

        Button(onClick = {
            scope.launch {
                Encatch.init(
                    apiKey = "YOUR_API_KEY",
                    config = EncatchConfig(
                        apiBaseUrl = mockServerBaseUrl,
                        webHost = mockServerBaseUrl,
                        debugMode = true,
                    ),
                )
                status = "Initialized: ${Encatch.isInitialized}"
            }
        }, modifier = Modifier.fillMaxWidth().testTag("initButton")) {
            Text("Init SDK")
        }

        Button(onClick = {
            scope.launch {
                Encatch.showForm("cmp-modal-form-id")
                status = "showForm(\"cmp-modal-form-id\") called"
            }
        }, modifier = Modifier.fillMaxWidth().testTag("showModalButton")) {
            Text("Show modal form")
        }

        Text("Inline form slot:", style = MaterialTheme.typography.titleMedium)

        EncatchInlineForm(
            formId = "cmp-inline-form-id",
            modifier = Modifier.fillMaxWidth().height(320.dp),
        )

        Button(onClick = {
            scope.launch {
                Encatch.showForm("cmp-inline-form-id")
                status = "showForm(\"cmp-inline-form-id\") called"
            }
        }, modifier = Modifier.fillMaxWidth().testTag("showInlineButton")) {
            Text("Show inline form")
        }
    }
}
