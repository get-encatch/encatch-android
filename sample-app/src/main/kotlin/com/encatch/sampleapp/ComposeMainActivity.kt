package com.encatch.sampleapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.encatch.android.EncatchInlineFormView
import com.encatch.core.Encatch
import com.encatch.core.EncatchConfig
import kotlinx.coroutines.launch

/**
 * Demonstrates the Jetpack Compose integration path: the SDK's classic-Views UI
 * (`EncatchInlineFormView`, and the modal `EncatchFormDialog` via `EncatchFormHost`, already
 * installed application-wide in `SampleApplication`) embedded in a Compose screen via
 * `AndroidView` interop — no SDK code change needed, since `:android` already ships plain
 * Android Views.
 */
class ComposeMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ComposeSampleScreen()
                }
            }
        }
    }
}

@Composable
private fun ComposeSampleScreen() {
    var status by remember { mutableStateOf("Not initialized") }
    val scope = androidx.compose.ui.platform.LocalContext.current
    val lifecycleScope = (scope as ComponentActivity).lifecycleScope

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Encatch Compose Sample", style = MaterialTheme.typography.headlineSmall)
        Text(status, style = MaterialTheme.typography.bodySmall)

        Button(onClick = {
            lifecycleScope.launch {
                val mockBaseUrl = BuildConfig.MOCK_SERVER_BASE_URL.takeIf { it.isNotEmpty() }
                val config = if (mockBaseUrl != null) {
                    EncatchConfig(apiBaseUrl = mockBaseUrl, webHost = mockBaseUrl, debugMode = true)
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
            lifecycleScope.launch { Encatch.showForm("compose-modal-form-id") }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Show modal form")
        }

        Text("Inline form slot:", style = MaterialTheme.typography.titleMedium)

        AndroidView(
            modifier = Modifier.fillMaxWidth().height(320.dp),
            factory = { context ->
                EncatchInlineFormView(context).apply { formId = "compose-inline-form-id" }
            },
        )

        Button(onClick = {
            lifecycleScope.launch { Encatch.showForm("compose-inline-form-id") }
        }, modifier = Modifier.fillMaxWidth()) {
            Text("Show inline form")
        }
    }
}
