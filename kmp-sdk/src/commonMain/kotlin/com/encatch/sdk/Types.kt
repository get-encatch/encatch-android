package com.encatch.sdk

import kotlinx.serialization.json.JsonElement

/**
 * Plain multiplatform mirrors of `:core`'s public request/response/config types
 * (`core/src/commonMain/kotlin/com/encatch/core/Types.kt`, `EncatchConfig.kt`).
 *
 * Why these are redeclared here instead of imported directly from `:core`: `:core` only targets
 * `androidTarget()`/`jvm("desktop")` (iOS moved to the pure-Swift `ios-native/` SDK). Gradle's
 * Kotlin Multiplatform variant
 * matching requires a dependency to publish a compatible target for every target of the consuming
 * source set; since `:core` has no `iosArm64`/`iosSimulatorArm64` target, `:kmp-sdk`'s `commonMain`
 * (shared by both Android and iOS) cannot depend on `:core` at all — verified empirically
 * (`:kmp-sdk:compileKotlinIosSimulatorArm64` fails variant resolution against `:core` with "no
 * matching variant... required org.jetbrains.kotlin.native.target 'ios_simulator_arm64'").
 *
 * So instead: `:core`'s types are reused with zero cost on the Android actual (`Encatch.android.kt`
 * forwards directly to `com.encatch.core.Encatch`, using `:core`'s real types, no conversion). On
 * iOS, there is no equivalent shared Kotlin type to reuse (the bridge speaks its own `@objc` mirror
 * types in `com.encatch.bridge.*`, generated from `EncatchBridge.swift`) — so this module owns a
 * small set of flat, platform-neutral data classes here, and both actuals convert to/from them
 * (`Encatch.android.kt` -> `com.encatch.core.*`, `Encatch.ios.kt` -> `com.encatch.bridge.*`). This
 * is exactly the same tradeoff `EncatchBridge.swift` already made one boundary further in (see its
 * own file-level doc comment): flat types get small mirror classes; the one deeply-nested type,
 * `SubmitFormRequest`, gets JSON-string passthrough instead of a hand-mirrored class tree (see
 * [Encatch.submitForm]'s doc comment).
 */
enum class Theme {
    LIGHT, DARK, SYSTEM;

    companion object
}

enum class ResetMode {
    ALWAYS, ON_COMPLETE, NEVER;

    companion object
}

enum class TriggerType {
    AUTOMATIC, MANUAL;

    companion object
}

sealed class ContextValue {
    data class StringValue(val value: String) : ContextValue()
    data class NumberValue(val value: Double) : ContextValue()
    data class BooleanValue(val value: Boolean) : ContextValue()

    /** Epoch millis. Serialized to an ISO-8601 string on the wire on both platforms. */
    data class DateValue(val epochMillis: Long) : ContextValue()
}

/**
 * Mirrors `:core`'s `ShowFormInterceptorPayload` (and, on iOS, `EncatchBridgeShowFormInterceptorPayload`
 * in `EncatchBridge.swift`) — passed to [EncatchConfig.onBeforeShowForm]. `formConfig` (the full
 * form definition, `ShowFormResponse`/`ShowFormConfiguration`) is not mirrored as a typed field
 * here — same "flat mirror vs. JSON passthrough vs. out of scope" tradeoff [Encatch.submitForm]'s
 * doc comment describes — but [formConfigJson] exposes its `questionnaireFields` (plus enough of
 * the config tree to read a form title) as a JSON-string passthrough, the same pattern
 * [prefillResponses]/[context] use one layer further in, so a host app can hand-render its own
 * form UI from the interceptor payload alone (see [buildSubmitRequest] for the matching submit
 * path).
 */
data class ShowFormInterceptorPayload(
    val formId: String,
    val resetMode: ResetMode,
    val triggerType: TriggerType,
    val prefillResponses: Map<String, JsonElement> = emptyMap(),
    val locale: String? = null,
    val theme: Theme? = null,
    val context: Map<String, JsonElement>? = null,
    /** JSON encoding of `:core`'s `ShowFormResponse` (the full form config), or `null` if unavailable. */
    val formConfigJson: String? = null,
)

data class EncatchConfig(
    val apiBaseUrl: String? = null,
    val webHost: String? = null,
    val theme: Theme = Theme.SYSTEM,
    val isFullScreen: Boolean = false,
    val debugMode: Boolean = false,
    val appVersion: String? = null,
    /**
     * Called before any form is shown (manual or automatic). Return `false` to block the SDK form
     * from opening — the host app can then show its own UI using [ShowFormInterceptorPayload].
     * Free to await anything (a coroutine, user input, a network check) before answering: on iOS
     * this is bridged as a plain callback the native side invokes whenever it has an answer, not a
     * synchronous decision (see `Encatch.ios.kt`'s `toBridge()` for the bridging mechanics).
     */
    val onBeforeShowForm: (suspend (ShowFormInterceptorPayload) -> Boolean)? = null,
)

data class UserTraits(
    val set: Map<String, JsonElement>? = null,
    val setOnce: Map<String, JsonElement>? = null,
    val increment: Map<String, Double>? = null,
    val decrement: Map<String, Double>? = null,
    val unset: List<String>? = null,
)

data class SecureOptions(
    val signature: String,
    val generatedDateTimeInUtc: String? = null,
)

/**
 * One completed SDK HTTP call (request + response), delivered to `Encatch.setOnNetworkLog`
 * callbacks for host-app debugging tools. Only emitted when `EncatchConfig.debugMode` is
 * enabled; the API key header is always masked to its last 5 characters. Covers all JSON POST
 * endpoints and the multipart upload (logged with a `<multipart>` summary line in place of the
 * binary body) — only the Q&A-with-AI SSE stream is not logged.
 */
data class NetworkLogEntry(
    val timestampMs: Long,
    val method: String,
    val endpoint: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int,
    val responseBody: String,
    val durationMs: Long,
    val error: String?,
)

data class IdentifyOptions(
    val locale: String? = null,
    val country: String? = null,
    val secure: SecureOptions? = null,
)

data class ShowFormOptions(
    val reset: ResetMode = ResetMode.ALWAYS,
    val context: Map<String, ContextValue> = emptyMap(),
)

data class StartSessionOptions(
    val skipImmediatePing: Boolean = false,
    val skipImmediateTrackScreen: Boolean = false,
)

data class RefineTextRequest(
    val questionId: String,
    val feedbackConfigurationId: String,
    val userText: String,
)

data class RefineTextResponse(
    val message: String? = null,
    val refinedText: String? = null,
    val status: Int? = null,
    val error: String? = null,
)

data class QnaWithAiConversationTurn(
    val question: String,
    val answer: String,
)

data class QnaWithAiRequest(
    val feedbackConfigurationId: String,
    val questionId: String,
    val conversation: List<QnaWithAiConversationTurn> = emptyList(),
)

/**
 * A local file to upload. Bytes-only (no content-URI variant) — matches the restriction
 * `EncatchBridge.swift`'s `uploadFile` already imposes at the Swift/ObjC boundary (`.contentUri` is
 * an Android-only concept, and one `:kmp-sdk` public signature must work identically on both
 * platforms). Android consumers who need `.contentUri` behavior can still call `:core`'s
 * `Encatch.uploadFile` directly.
 */
data class UploadFileRequest(
    val feedbackConfigurationId: String,
    val questionId: String,
    val fileBytes: ByteArray,
    val fileName: String = "upload",
    val mimeType: String? = null,
    val onProgress: ((Int) -> Unit)? = null,
)

data class UploadFileResponse(
    val fileUrl: String,
)

/** Event types emitted by the SDK via [Encatch.on]. Mirrors `core.EventType`'s wire values. */
enum class EventType(val wireValue: String) {
    FORM_SHOW("form:show"),
    FORM_STARTED("form:started"),
    FORM_SUBMIT("form:submit"),
    FORM_COMPLETE("form:complete"),
    FORM_CLOSE("form:close"),
    FORM_DISMISSED("form:dismissed"),
    FORM_ERROR("form:error"),
    FORM_SECTION_CHANGE("form:section:change"),
    FORM_ANSWERED("form:answered"),
    FORM_REMIND_ME_LATER("form:remindmelater"),
    FORM_CTA_TRIGGERED("form:ctaTriggered"),
    ;

    companion object {
        fun fromWire(value: String): EventType? = entries.find { it.wireValue == value }
    }
}

data class EventPayload(
    val formId: String? = null,
    val timestamp: Long = 0,
    val data: Map<String, JsonElement>? = null,
)

typealias EventCallback = (EventType, EventPayload) -> Unit
