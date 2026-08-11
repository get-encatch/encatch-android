package com.encatch.composesample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Launcher entry point for the Compose Multiplatform sample variant. Registered by the consuming
 * app's manifest (see `sample-app`'s `AndroidManifest.xml`), since library modules can't declare
 * launcher activities themselves.
 */
class ComposeMultiplatformSampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mockBaseUrl = intent.getStringExtra(EXTRA_MOCK_SERVER_BASE_URL)?.takeIf { it.isNotEmpty() }
        setContent {
            ComposeSampleApp(mockBaseUrl)
        }
    }

    companion object {
        const val EXTRA_MOCK_SERVER_BASE_URL = "mock_server_base_url"
    }
}
