package com.encatch.sdk

import kotlinx.serialization.json.JsonElement

/**
 * Plain multiplatform mirrors of `:core`'s public request/response/config types
 * (`core/src/commonMain/kotlin/com/encatch/core/Types.kt`, `EncatchConfig.kt`).
 *
 * Why these are redeclared here instead of imported directly from `:core`: `:core` only targets
 * `androidTarget()`/`jvm("desktop")` (iOS moved to the pure-Swift `ios-native/` SDK — see
 * /Users/godwin/.claude/plans/stateless-floating-ripple.md). Gradle's Kotlin Multiplatform variant
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
    ALWAYS, ON_COMPLETE, NEVER,
}

sealed class ContextValue {
    data class StringValue(val value: String) : ContextValue()
    data class NumberValue(val value: Double) : ContextValue()
    data class BooleanValue(val value: Boolean) : ContextValue()

    /** Epoch millis. Serialized to an ISO-8601 string on the wire on both platforms. */
    data class DateValue(val epochMillis: Long) : ContextValue()
}

data class EncatchConfig(
    val apiBaseUrl: String? = null,
    val webHost: String? = null,
    val theme: Theme = Theme.SYSTEM,
    val isFullScreen: Boolean = false,
    val debugMode: Boolean = false,
    val appVersion: String? = null,
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
