package com.encatch.kmptester

import com.encatch.sdk.BuildSubmitRequestOptions
import com.encatch.sdk.Encatch
import com.encatch.sdk.EncatchConfig
import com.encatch.sdk.EventPayload
import com.encatch.sdk.EventType
import com.encatch.sdk.NativeFormResponse
import com.encatch.sdk.Theme
import com.encatch.sdk.buildSubmitRequest
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * [initSdk]'s `onIntercept` param is a plain (non-`suspend`) callback, not a `suspend` function
 * type, even though it wraps `:kmp-sdk`'s `suspend (ShowFormInterceptorPayload) -> Boolean`
 * interceptor internally: Kotlin/Native's ObjC export only turns `suspend` *member* functions into
 * completion-handler methods — a `suspend` function TYPE used as a parameter doesn't bridge to
 * Swift cleanly. So the platform UI gets a plain `(formId, formConfigJson, completion) -> Unit`
 * callback instead (a totally ordinary Swift/Kotlin closure) and calls `completion(allow)`
 * whenever it has an answer; [suspendCancellableCoroutine] on this side converts that back into
 * the suspend result `:kmp-sdk` needs. Same "plain callback + explicit completion" technique
 * `EncatchBridge.swift`/`Encatch.ios.kt` already use for this exact interceptor one boundary
 * further out.
 *
 * The interceptor always blocks a matching form id (never lets it through) and hands the platform
 * UI a queue-able item + [formConfigJson] to hand-render a custom native form from — see
 * [buildSubmitRequest]/[submitNativeForm] for the matching submit path.
 */
object TesterController {

    @Throws(Exception::class)
    suspend fun initSdk(
        apiKey: String,
        baseUrl: String?,
        webHost: String?,
        interceptorFormId: String?,
        onIntercept: (formId: String, formConfigJson: String?, completion: (Boolean) -> Unit) -> Unit,
    ) {
        Encatch.init(
            apiKey = apiKey,
            config = EncatchConfig(
                apiBaseUrl = baseUrl,
                webHost = webHost,
                debugMode = true,
                onBeforeShowForm = { payload ->
                    if (payload.formId == interceptorFormId) {
                        suspendCancellableCoroutine { cont ->
                            onIntercept(payload.formId, payload.formConfigJson) { allow -> cont.resume(allow) }
                        }
                    } else {
                        true
                    }
                },
            ),
        )
    }

    @Throws(Exception::class)
    suspend fun identify(userName: String, email: String?, displayName: String?) {
        val traits = if (email.isNullOrBlank() && displayName.isNullOrBlank()) {
            null
        } else {
            val set = buildMap<String, kotlinx.serialization.json.JsonElement> {
                if (!email.isNullOrBlank()) put("email", JsonPrimitive(email))
                if (!displayName.isNullOrBlank()) put("display_name", JsonPrimitive(displayName))
            }
            com.encatch.sdk.UserTraits(set = set)
        }
        Encatch.identifyUser(userName, traits = traits)
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
    suspend fun showPrefilledForm(formId: String, questionId: String, value: String) {
        Encatch.addToResponse(questionId, value)
        Encatch.showForm(formId)
    }

    @Throws(Exception::class)
    suspend fun dismissForm(formId: String?) {
        Encatch.dismissForm(formId)
    }

    fun emitEvent(eventWireValue: String, formId: String?) {
        val type = EventType.entries.find { it.wireValue == eventWireValue } ?: return
        Encatch.emitEvent(type, EventPayload(formId = formId, timestamp = 0))
    }

    @Throws(Exception::class)
    suspend fun submitNativeForm(formId: String, questionIds: List<String>, types: List<String>, values: List<String?>) {
        val responses = questionIds.indices.map { i ->
            NativeFormResponse(questionId = questionIds[i], type = types[i], value = values[i])
        }
        val requestJson = buildSubmitRequest(BuildSubmitRequestOptions(formConfigurationId = formId), responses)
        Encatch.submitForm(requestJson)
    }

    fun setLocale(locale: String) {
        Encatch.setLocale(locale)
    }

    fun setCountry(country: String) {
        Encatch.setCountry(country)
    }

    /** Cycles system -> light -> dark -> system and returns the new theme's name. */
    fun cycleTheme(): String {
        val next = when (Encatch.theme) {
            Theme.SYSTEM -> Theme.LIGHT
            Theme.LIGHT -> Theme.DARK
            Theme.DARK -> Theme.SYSTEM
        }
        Encatch.setTheme(next)
        return next.name
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
