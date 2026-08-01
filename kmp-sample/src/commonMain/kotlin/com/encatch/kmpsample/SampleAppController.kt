package com.encatch.kmpsample

import com.encatch.core.Encatch
import com.encatch.core.EncatchConfig

/**
 * Shared business logic for the KMP host sample app — called directly from both `androidMain`'s
 * Activity and `iosMain`'s native screen, proving a single commonMain call site works identically
 * on both platforms via ordinary Gradle KMP dependency resolution (Android) and the XCFramework
 * (iOS), with no platform-specific wrapping needed for these plain, non-JsonElement calls.
 */
object SampleAppController {

    @Throws(Exception::class)
    suspend fun initSdk(mockServerBaseUrl: String?): String {
        val config = if (mockServerBaseUrl != null) {
            EncatchConfig(apiBaseUrl = mockServerBaseUrl, webHost = mockServerBaseUrl, debugMode = true)
        } else {
            EncatchConfig(debugMode = true)
        }
        Encatch.init("YOUR_API_KEY", config)
        return "Initialized: ${Encatch.isInitialized}, deviceId=${Encatch.deviceId}"
    }

    @Throws(Exception::class)
    suspend fun showModalForm(): String {
        Encatch.showForm("kmp-modal-form-id")
        return "showForm(\"kmp-modal-form-id\") called"
    }

    @Throws(Exception::class)
    suspend fun showInlineForm(): String {
        Encatch.showForm("kmp-inline-form-id")
        return "showForm(\"kmp-inline-form-id\") called"
    }
}
