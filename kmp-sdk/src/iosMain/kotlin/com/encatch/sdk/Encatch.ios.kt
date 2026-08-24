@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.encatch.sdk

import com.encatch.bridge.EncatchBridge
import com.encatch.bridge.EncatchBridgeConfig
import com.encatch.bridge.EncatchBridgeEventPayload
import com.encatch.bridge.EncatchBridgeIdentifyOptions
import com.encatch.bridge.EncatchBridgeRefineTextResponse
import com.encatch.bridge.EncatchBridgeSecureOptions
import com.encatch.bridge.EncatchBridgeShowFormInterceptorPayload
import com.encatch.bridge.EncatchBridgeShowFormOptions
import com.encatch.bridge.EncatchBridgeStartSessionOptions
import com.encatch.bridge.EncatchBridgeUploadFileResponse
import com.encatch.bridge.EncatchBridgeUserTraits
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import platform.Foundation.NSData
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSDate
import platform.Foundation.create

/**
 * iOS backing for [Encatch] — forwards to the pure-Swift `ios-native` SDK via `EncatchBridge` and
 * its `EncatchBridge*` mirror types, the Kotlin/Native cinterop bindings generated from
 * `ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift`'s `@objc` facade (see
 * `kmp-sdk/src/nativeInterop/cinterop/EncatchBridge.def`). The Swift side is completion-handler
 * based (Swift `async`/`throws` isn't cinterop-representable); each call wraps back into a suspend
 * function via `suspendCancellableCoroutine`, following the pattern established in
 * `compose-sample/src/iosMain/kotlin/com/encatch/composesample/SampleSdk.ios.kt`.
 *
 * Deviation from idiomatic Kotlin/Native ObjC interop: the generated bindings do NOT synthesize
 * Kotlin `val`/`var` properties for the header's Objective-C `@property` declarations (unlike, say,
 * `platform.UIKit`'s prebuilt bindings) — calls go through the raw getter/setter methods
 * (`.shared()`, `.isInitialized()`, `.setApiBaseUrl(...)`, etc.) instead, same as `SampleSdk.ios.kt`.
 */
actual object Encatch {

    // ============================================================================
    // Initialisation
    // ============================================================================

    actual suspend fun init(apiKey: String, config: EncatchConfig?) {
        // Installing the modal form host needs no Application-equivalent reference on iOS
        // (unlike Android's EncatchFormHost.install(application), which needs a real Application
        // instance to observe Activity lifecycle) — so it's safe to do this automatically here,
        // making iOS customers' setup genuinely zero-plumbing. Idempotent (installFormHost no-ops
        // after the first call), so this is safe even if init() is called more than once.
        EncatchBridge.installFormHost()
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().initializeWithApiKey(apiKey, config?.toBridge()) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    actual val isInitialized: Boolean get() = EncatchBridge.shared().isInitialized()

    // ============================================================================
    // Identity
    // ============================================================================

    actual suspend fun identifyUser(userName: String, traits: UserTraits?, options: IdentifyOptions?) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().identifyUserWithUserName(
                userName,
                traits?.toBridge(),
                options?.toBridge(),
            ) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    actual fun setLocale(locale: String) {
        EncatchBridge.shared().setLocale(locale)
    }

    actual fun setCountry(country: String) {
        EncatchBridge.shared().setCountry(country)
    }

    actual fun setTheme(theme: Theme) {
        EncatchBridge.shared().setTheme(theme.wireValue)
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    actual suspend fun trackEvent(eventName: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().trackEvent(eventName) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    actual suspend fun trackFormEvent(eventName: String, feedbackConfigurationId: String?) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().trackFormEvent(eventName, feedbackConfigurationId) {
                cont.resume(Unit)
            }
        }
    }

    actual suspend fun trackScreen(screenName: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().trackScreen(screenName) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    // ============================================================================
    // Form display
    // ============================================================================

    actual suspend fun showForm(formId: String, options: ShowFormOptions?) {
        suspendCancellableCoroutine<Unit> { cont ->
            if (options == null) {
                EncatchBridge.shared().showForm(formId) { error ->
                    if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
                }
            } else {
                EncatchBridge.shared().showForm(formId, options.toBridge()) { error ->
                    if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
                }
            }
        }
    }

    actual suspend fun dismissForm(formConfigurationId: String?) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().dismissForm(formConfigurationId) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    // ============================================================================
    // Form response helpers
    // ============================================================================

    actual fun addToResponse(questionId: String, value: Any?) {
        EncatchBridge.shared().addToResponseWithQuestionId(questionId, value)
    }

    actual fun getPendingResponses(): Map<String, Any?> {
        val raw: Map<Any?, *> = EncatchBridge.shared().getPendingResponses()
        return raw.entries.associate { (k, v) -> (k as String) to v }
    }

    actual fun clearPendingResponses() {
        EncatchBridge.shared().clearPendingResponses()
    }

    // ============================================================================
    // Submit form
    // ============================================================================

    actual suspend fun submitForm(requestJson: String) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().submitForm(requestJson) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

    actual suspend fun refineText(params: RefineTextRequest): RefineTextResponse =
        suspendCancellableCoroutine { cont ->
            EncatchBridge.shared().refineTextWithQuestionId(
                params.questionId,
                params.feedbackConfigurationId,
                params.userText,
            ) { response, error ->
                if (error != null || response == null) {
                    cont.resumeWithException(RuntimeException(error?.localizedDescription ?: "refineText failed"))
                } else {
                    cont.resume(response.toSdk())
                }
            }
        }

    actual suspend fun streamQnaWithAi(params: QnaWithAiRequest, onChunk: (String) -> Unit, onDone: (String) -> Unit) {
        suspendCancellableCoroutine<Unit> { cont ->
            val conversation = params.conversation.map { mapOf("question" to it.question, "answer" to it.answer) }
            EncatchBridge.shared().streamQnaWithAiWithFeedbackConfigurationId(
                params.feedbackConfigurationId,
                params.questionId,
                conversation,
                onChunk = { chunk -> chunk?.let(onChunk) },
                onDone = { done ->
                    done?.let(onDone)
                    if (cont.isActive) cont.resume(Unit)
                },
                onError = { error ->
                    if (cont.isActive) cont.resumeWithException(RuntimeException(error?.localizedDescription ?: "streamQnaWithAi failed"))
                },
            )
        }
    }

    actual suspend fun uploadFile(params: UploadFileRequest): UploadFileResponse =
        suspendCancellableCoroutine { cont ->
            EncatchBridge.shared().uploadFileWithFeedbackConfigurationId(
                params.feedbackConfigurationId,
                params.questionId,
                params.fileBytes.toNSData(),
                params.fileName,
                params.mimeType,
                onProgress = params.onProgress?.let { progress -> { percent: Long -> progress(percent.toInt()) } },
            ) { response, error ->
                if (error != null || response == null) {
                    cont.resumeWithException(RuntimeException(error?.localizedDescription ?: "uploadFile failed"))
                } else {
                    cont.resume(response.toSdk())
                }
            }
        }

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    actual suspend fun clearAll() {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().clearAllWithCompletion { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    // ============================================================================
    // Session management
    // ============================================================================

    actual suspend fun startSession(options: StartSessionOptions?) {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().startSession(options?.toBridge()) { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    actual fun pauseSession() {
        EncatchBridge.shared().pauseSession()
    }

    actual fun resumeSession() {
        EncatchBridge.shared().resumeSession()
    }

    actual suspend fun stopSession() {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().stopSessionWithCompletion { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    actual suspend fun resetUser() {
        suspendCancellableCoroutine<Unit> { cont ->
            EncatchBridge.shared().resetUserWithCompletion { error ->
                if (error != null) cont.resumeWithException(RuntimeException(error.localizedDescription)) else cont.resume(Unit)
            }
        }
    }

    actual fun setFormVisible(visible: Boolean) {
        EncatchBridge.shared().setFormVisible(visible)
    }

    actual fun flushRetryQueue() {
        EncatchBridge.shared().flushRetryQueue()
    }

    // ============================================================================
    // Events
    // ============================================================================

    actual fun on(callback: EventCallback): () -> Unit =
        EncatchBridge.shared().onEvent { wireType, bridgePayload ->
            val type = wireType?.let { EventType.fromWire(it) }
            if (type != null && bridgePayload != null) callback(type, bridgePayload.toSdk())
        }

    actual fun emitEvent(eventType: EventType, payload: EventPayload) {
        val dataJson = payload.data?.let { JsonObject(it).toString() }
        EncatchBridge.shared().emitEvent(eventType.wireValue, payload.formId, dataJson)
    }

    actual fun setOnNetworkLog(callback: ((NetworkLogEntry) -> Unit)?) {
        if (callback == null) {
            EncatchBridge.shared().setOnNetworkLog(null)
            return
        }
        EncatchBridge.shared().setOnNetworkLog { entry ->
            if (entry != null) {
                fun parseHeaders(json: String): Map<String, String> = runCatching {
                    (kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject)
                        ?.mapValues { (it.value as? JsonPrimitive)?.content ?: it.value.toString() }
                }.getOrNull() ?: emptyMap()
                val headers = parseHeaders(entry.requestHeadersJSON())
                val responseHeaders = parseHeaders(entry.responseHeadersJSON())
                callback(
                    NetworkLogEntry(
                        timestampMs = entry.timestampMs(),
                        method = entry.method(),
                        endpoint = entry.endpoint(),
                        url = entry.url(),
                        requestHeaders = headers,
                        requestBody = entry.requestBody(),
                        status = entry.status().toInt(),
                        responseBody = entry.responseBody(),
                        durationMs = entry.durationMs(),
                        error = entry.error(),
                        responseHeaders = responseHeaders,
                    ),
                )
            }
        }
    }

    actual fun stop() {
        EncatchBridge.shared().stop()
    }

    // ============================================================================
    // Getters
    // ============================================================================

    actual val apiKey: String? get() = EncatchBridge.shared().apiKey()
    actual val baseUrl: String get() = EncatchBridge.shared().baseUrl()
    actual val webHost: String get() = EncatchBridge.shared().webHost()
    actual val isFullScreen: Boolean get() = EncatchBridge.shared().isFullScreen()
    actual val theme: Theme get() = Theme.fromWire(EncatchBridge.shared().theme())
    actual val locale: String? get() = EncatchBridge.shared().locale()
    actual val deviceId: String? get() = EncatchBridge.shared().deviceId()
    actual val sessionId: String? get() = EncatchBridge.shared().sessionId()
    actual val userName: String? get() = EncatchBridge.shared().userName()
    actual val userId: String? get() = EncatchBridge.shared().userId()
    actual val debugMode: Boolean get() = EncatchBridge.shared().debugMode()
}

// ============================================================================
// Type conversions: com.encatch.sdk.* (Types.kt) <-> com.encatch.bridge.EncatchBridge*
// ============================================================================

private val Theme.wireValue: String
    get() = when (this) {
        Theme.LIGHT -> "light"
        Theme.DARK -> "dark"
        Theme.SYSTEM -> "system"
    }

private fun Theme.Companion.fromWire(value: String?): Theme = when (value?.lowercase()) {
    "light" -> Theme.LIGHT
    "dark" -> Theme.DARK
    else -> Theme.SYSTEM
}

private val ResetMode.wireValue: String
    get() = when (this) {
        ResetMode.ALWAYS -> "always"
        ResetMode.ON_COMPLETE -> "on-complete"
        ResetMode.NEVER -> "never"
    }

private fun ResetMode.Companion.fromWire(value: String?): ResetMode = when (value) {
    "on-complete" -> ResetMode.ON_COMPLETE
    "never" -> ResetMode.NEVER
    else -> ResetMode.ALWAYS
}

private fun TriggerType.Companion.fromWire(value: String?): TriggerType = when (value) {
    "manual" -> TriggerType.MANUAL
    else -> TriggerType.AUTOMATIC
}

private fun EncatchBridgeShowFormInterceptorPayload.toSdk(): ShowFormInterceptorPayload = ShowFormInterceptorPayload(
    formId = formId(),
    resetMode = ResetMode.fromWire(resetMode()),
    triggerType = TriggerType.fromWire(triggerType()),
    prefillResponses = prefillResponsesJSON()?.let { json ->
        kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
    } ?: JsonObject(emptyMap()),
    locale = locale(),
    theme = theme()?.let { Theme.fromWire(it) },
    context = contextJSON()?.let { json ->
        kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
    },
    formConfigJson = formConfigJSON(),
)

/** Flattens a [JsonElement] into plain Kotlin values so it can cross the cinterop NSDictionary boundary. */
private fun JsonElement.toPlain(): Any? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        this.isString -> content
        booleanOrNull != null -> booleanOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
    is JsonArray -> map { it.toPlain() }
    is JsonObject -> mapValues { it.value.toPlain() }
}

private fun EncatchConfig.toBridge(): EncatchBridgeConfig = EncatchBridgeConfig().apply {
    setApiBaseUrl(apiBaseUrl)
    setWebHost(webHost)
    setDebugMode(debugMode)
    setIsFullScreen(isFullScreen)
    setAppVersion(appVersion)
    setTheme(theme.wireValue)
    val interceptor = onBeforeShowForm
    if (interceptor != null) {
        // The bridge property is a plain callback, not an `async` closure — see
        // `EncatchBridgeInterceptorCallback`'s doc comment in EncatchBridge.swift for why.
        // `interceptor` is a suspend function; launching it in a coroutine and calling `completion`
        // whenever it resolves is exactly the "listener implemented, auto-callback at the end"
        // pattern this needs — no suspend/async has to cross the ObjC boundary itself.
        setOnBeforeShowForm { bridgePayload, completion ->
            CoroutineScope(Dispatchers.Main).launch {
                val allow = bridgePayload?.let { interceptor(it.toSdk()) } ?: true
                completion?.invoke(allow)
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun UserTraits.toBridge(): EncatchBridgeUserTraits = EncatchBridgeUserTraits().apply {
    setSet(set?.mapValues { it.value.toPlain() } as Map<Any?, *>?)
    setSetOnce(setOnce?.mapValues { it.value.toPlain() } as Map<Any?, *>?)
    setIncrement(increment as Map<Any?, *>?)
    setDecrement(decrement as Map<Any?, *>?)
    setUnset(unset)
}

private fun SecureOptions.toBridge(): EncatchBridgeSecureOptions =
    EncatchBridgeSecureOptions(signature = signature).apply {
        setGeneratedDateTimeInUtc(generatedDateTimeInUtc)
    }

private fun IdentifyOptions.toBridge(): EncatchBridgeIdentifyOptions = EncatchBridgeIdentifyOptions().apply {
    setLocale(locale)
    setCountry(country)
    setSecure(secure?.toBridge())
}

/**
 * Epoch millis -> ISO-8601 string, matching the bridge's documented workaround for date context
 * values. `NSDate`'s Kotlin/Native constructor only takes `timeIntervalSinceReferenceDate` (seconds
 * since 2001-01-01T00:00:00Z), so the epoch (1970-01-01) offset is subtracted by hand.
 */
private const val NS_TIME_INTERVAL_SINCE_1970_TO_REFERENCE_DATE = 978307200.0

private fun isoStringFromEpochMillis(epochMillis: Long): String {
    val secondsSince1970 = epochMillis / 1000.0
    val date = NSDate(timeIntervalSinceReferenceDate = secondsSince1970 - NS_TIME_INTERVAL_SINCE_1970_TO_REFERENCE_DATE)
    return NSISO8601DateFormatter().stringFromDate(date)
}

private fun ContextValue.toPlain(): Any = when (this) {
    is ContextValue.StringValue -> value
    is ContextValue.NumberValue -> value
    is ContextValue.BooleanValue -> value
    is ContextValue.DateValue -> isoStringFromEpochMillis(epochMillis)
}

@Suppress("UNCHECKED_CAST")
private fun ShowFormOptions.toBridge(): EncatchBridgeShowFormOptions = EncatchBridgeShowFormOptions().apply {
    setReset(reset.wireValue)
    setContext(context.mapValues { it.value.toPlain() }.takeIf { it.isNotEmpty() } as Map<Any?, *>?)
}

private fun StartSessionOptions.toBridge(): EncatchBridgeStartSessionOptions = EncatchBridgeStartSessionOptions().apply {
    setSkipImmediatePing(skipImmediatePing)
    setSkipImmediateTrackScreen(skipImmediateTrackScreen)
}

private fun EncatchBridgeRefineTextResponse.toSdk(): RefineTextResponse = RefineTextResponse(
    message = message(),
    refinedText = refinedText(),
    status = status()?.intValue,
    error = error(),
)

private fun EncatchBridgeUploadFileResponse.toSdk(): UploadFileResponse = UploadFileResponse(fileUrl = fileUrl())

private fun EncatchBridgeEventPayload.toSdk(): EventPayload = EventPayload(
    formId = formId(),
    timestamp = timestamp(),
    data = dataJSON()?.let { json ->
        kotlinx.serialization.json.Json.parseToJsonElement(json) as? JsonObject
    },
)

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
