package com.encatch.kmptester

import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig
import com.encatch.sdk.EventType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared business logic for the KMP tester — same call site used by both the native Android
 * Activity (`MainActivity.kt`) and the native SwiftUI host (`encatch-kmp-tester-ios/`), proving
 * one commonMain layer drives two fully-native UIs on top of `:kmp-sdk`. Suspend functions here
 * are callable directly from Swift as `async throws` (Kotlin/Native exports them as
 * completion-handler methods, which Swift's importer bridges automatically) — no Kotlin/Native
 * UIKit code needed on the iOS side, unlike kmp-sample's `KmpSampleViewController.kt`.
 *
 * `:kmp-sdk`'s `EncatchConfig` has no `onBeforeShowForm` yet (see its `Types.kt`), so this tester
 * has no interceptor screen — a known gap, unlike `encatch-android-tester`/`encatch-ios-tester`.
 */
object TesterController {

    @Throws(Exception::class)
    suspend fun initSdk(apiKey: String, baseUrl: String?, webHost: String?) {
        Encatch.init(
            apiKey = apiKey,
            config = EncatchConfig(apiBaseUrl = baseUrl, webHost = webHost, debugMode = true),
        )
    }

    @Throws(Exception::class)
    suspend fun identify(userName: String) {
        Encatch.identifyUser(userName)
    }

    @Throws(Exception::class)
    suspend fun trackEvent(name: String) {
        Encatch.trackEvent(name)
    }

    @Throws(Exception::class)
    suspend fun trackScreen(name: String) {
        Encatch.trackScreen(name)
    }

    @Throws(Exception::class)
    suspend fun showForm(formId: String) {
        Encatch.showForm(formId)
    }

    @Throws(Exception::class)
    suspend fun resetUser() {
        Encatch.resetUser()
    }

    @Throws(Exception::class)
    suspend fun clearAll() {
        Encatch.clearAll()
    }

    /**
     * Registers a listener and returns an unsubscribe function. `form:ctaTriggered`'s
     * `action`/`route` fields are pre-extracted for convenience — callers don't need to touch
     * `kotlinx.serialization.json` types themselves.
     */
    fun onEvent(callback: (eventWireValue: String, formId: String?, ctaAction: String?, ctaRoute: String?) -> Unit): () -> Unit {
        return Encatch.on { eventType, payload ->
            var action: String? = null
            var route: String? = null
            if (eventType == EventType.FORM_CTA_TRIGGERED) {
                action = (payload.data?.get("action") as? JsonPrimitive)?.contentOrNull
                route = (payload.data?.get("route") as? JsonPrimitive)?.contentOrNull
            }
            callback(eventType.wireValue, payload.formId, action, route)
        }
    }
}
