package com.encatch.kmpsample

/**
 * Thin per-platform seam so [SampleAppController] (shared commonMain business logic) can drive
 * whichever native SDK actually backs the current platform: Kotlin's `com.encatch.core.Encatch`
 * on Android (unchanged), and the pure Swift `ios-native` SDK via its `@objc` cinterop bridge on
 * iOS (see `ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift` and this file's `iosMain`
 * actual). See /Users/godwin/.claude/plans/stateless-floating-ripple.md for why iOS moved off
 * `:core`. Only the calls [SampleAppController] actually makes are mirrored here.
 */
expect object SampleSdk {
    val isInitialized: Boolean
    val deviceId: String?

    suspend fun init(apiKey: String, apiBaseUrl: String?, webHost: String?, debugMode: Boolean)

    suspend fun showForm(formId: String)
}
