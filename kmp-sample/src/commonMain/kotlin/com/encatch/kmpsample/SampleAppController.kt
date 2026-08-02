package com.encatch.kmpsample

/**
 * Shared business logic for the KMP host sample app — called directly from both `androidMain`'s
 * Activity and `iosMain`'s native screen, proving a single commonMain call site works identically
 * on both platforms. Goes through [SampleSdk] rather than `com.encatch.core.Encatch` directly so
 * Android keeps using `:core` (via ordinary Gradle KMP dependency resolution) while iOS drives the
 * native Swift `ios-native` SDK (via Kotlin/Native cinterop) — see [SampleSdk]'s doc comment and
 * /Users/godwin/.claude/plans/stateless-floating-ripple.md.
 */
object SampleAppController {

    @Throws(Exception::class)
    suspend fun initSdk(mockServerBaseUrl: String?): String {
        SampleSdk.init(
            apiKey = "YOUR_API_KEY",
            apiBaseUrl = mockServerBaseUrl,
            webHost = mockServerBaseUrl,
            debugMode = true,
        )
        return "Initialized: ${SampleSdk.isInitialized}, deviceId=${SampleSdk.deviceId}"
    }

    @Throws(Exception::class)
    suspend fun showModalForm(): String {
        SampleSdk.showForm("kmp-modal-form-id")
        return "showForm(\"kmp-modal-form-id\") called"
    }

    @Throws(Exception::class)
    suspend fun showInlineForm(): String {
        SampleSdk.showForm("kmp-inline-form-id")
        return "showForm(\"kmp-inline-form-id\") called"
    }
}
