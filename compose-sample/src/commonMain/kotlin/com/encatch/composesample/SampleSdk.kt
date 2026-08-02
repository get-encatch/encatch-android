package com.encatch.composesample

/**
 * Thin per-platform seam so this commonMain UI can drive whichever native SDK actually backs the
 * current platform: Kotlin's `com.encatch.core.Encatch` on Android (unchanged — Android's native
 * language *is* Kotlin, so `:core` is a real native SDK there, not a shared layer), and the pure
 * Swift `ios-native` SDK via its `@objc` cinterop bridge on iOS (see
 * `ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift` and this file's `iosMain` actual).
 *
 * Only the calls `ComposeSampleScreen` actually makes are mirrored here — this is not a general
 * `Encatch` facade. See /Users/godwin/.claude/plans/stateless-floating-ripple.md for why iOS moved
 * off `:core`.
 */
expect object SampleSdk {
    val isInitialized: Boolean

    suspend fun init(apiKey: String, apiBaseUrl: String?, webHost: String?, debugMode: Boolean)

    suspend fun showForm(formId: String)
}
