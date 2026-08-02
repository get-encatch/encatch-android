package com.encatch.kmpsample

import com.encatch.core.Encatch
import com.encatch.core.EncatchConfig

/** Android backing for [SampleSdk] — forwards to `:core`'s Kotlin `Encatch` singleton, unchanged. */
actual object SampleSdk {
    actual val isInitialized: Boolean get() = Encatch.isInitialized
    actual val deviceId: String? get() = Encatch.deviceId

    actual suspend fun init(apiKey: String, apiBaseUrl: String?, webHost: String?, debugMode: Boolean) {
        val config = if (apiBaseUrl != null) {
            EncatchConfig(apiBaseUrl = apiBaseUrl, webHost = webHost ?: apiBaseUrl, debugMode = debugMode)
        } else {
            EncatchConfig(debugMode = debugMode)
        }
        Encatch.init(apiKey, config)
    }

    actual suspend fun showForm(formId: String) {
        Encatch.showForm(formId)
    }
}
