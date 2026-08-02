package com.encatch.kmpsample

import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig

/**
 * Shared business logic for the KMP host sample app — called directly from both `androidMain`'s
 * Activity and `iosMain`'s native screen, proving a single commonMain call site works identically
 * on both platforms. Goes through `:kmp-sdk`'s [Encatch] object directly now (no sample-owned
 * `SampleSdk` seam) — Android forwards to `:core`, iOS forwards through Kotlin/Native cinterop
 * onto the native Swift `ios-native` SDK, both internal to `:kmp-sdk`. See
 * /Users/godwin/.claude/plans/stateless-floating-ripple.md ("Publish real :kmp-sdk / :compose-sdk
 * libraries" — Phase 5).
 */
object SampleAppController {

    @Throws(Exception::class)
    suspend fun initSdk(mockServerBaseUrl: String?): String {
        Encatch.init(
            apiKey = "YOUR_API_KEY",
            config = EncatchConfig(
                apiBaseUrl = mockServerBaseUrl,
                webHost = mockServerBaseUrl,
                debugMode = true,
            ),
        )
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
