package com.encatch.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Default theme for forms. */
enum class Theme {
    LIGHT, DARK, SYSTEM;

    val wireValue: String
        get() = name.lowercase()

    companion object {
        fun fromWire(value: String?): Theme = when (value) {
            "light" -> LIGHT
            "dark" -> DARK
            else -> SYSTEM
        }
    }
}

enum class TriggerType {
    AUTOMATIC, MANUAL;

    val wireValue: String get() = name.lowercase()
}

/**
 * Reset mode for form data when showing a form.
 * - ALWAYS: Clear form data every time showForm is called (default)
 * - ON_COMPLETE: Clear form data only if form was previously completed
 * - NEVER: Never clear form data (preserve user's previous answers)
 */
enum class ResetMode {
    ALWAYS, ON_COMPLETE, NEVER;

    val wireValue: String
        get() = when (this) {
            ALWAYS -> "always"
            ON_COMPLETE -> "on-complete"
            NEVER -> "never"
        }

    companion object {
        fun fromWire(value: String?): ResetMode = when (value) {
            "on-complete" -> ON_COMPLETE
            "never" -> NEVER
            else -> ALWAYS
        }
    }
}

/** Arbitrary context value attached to a form submission. Dates are serialized to ISO strings before sending. */
sealed class ContextValue {
    data class StringValue(val value: String) : ContextValue()
    data class NumberValue(val value: Double) : ContextValue()
    data class BooleanValue(val value: Boolean) : ContextValue()
    /** Epoch millis; serialized to an ISO-8601 string on the wire, matching the RN SDK's Date handling. */
    data class DateValue(val epochMillis: Long) : ContextValue()
}

data class ShowFormOptions(
    val reset: ResetMode = ResetMode.ALWAYS,
    val context: Map<String, ContextValue> = emptyMap(),
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

data class StartSessionOptions(
    val skipImmediatePing: Boolean = false,
    val skipImmediateTrackScreen: Boolean = false,
)

/** Event types emitted by the SDK via [Encatch.on]. */
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
    FORM_CTA_TRIGGERED("form:ctaTriggered");

    companion object {
        fun fromWire(value: String): EventType? = entries.find { it.wireValue == value }
    }
}

data class EventPayload(
    val formId: String? = null,
    val timestamp: Long,
    val data: Map<String, JsonElement>? = null,
)

typealias EventCallback = (EventType, EventPayload) -> Unit

/** Wire-format payload for exit_form completion CTAs deferred to the native SDK timer. */
@Serializable
data class PendingCompletionCta(
    val action: String, // "dismiss" | "app_navigate" | "redirect_internal" | "redirect_external"
    val url: String? = null,
    val route: String? = null,
    val surface: String = "inApp", // "inApp" | "link"
    val trigger: String = "auto",
    val autoTriggerDelayMs: Long = 0,
)

data class ShowFormInterceptorPayload(
    val formId: String,
    val formConfig: ShowFormResponse,
    val resetMode: ResetMode,
    val triggerType: TriggerType,
    val prefillResponses: Map<String, JsonElement>,
    val locale: String? = null,
    val theme: Theme? = null,
    val context: Map<String, JsonElement>? = null,
)

data class ApiDeviceInfo(
    val deviceOs: String? = null,
    val deviceVersion: String? = null,
    val deviceOsVersion: String? = null,
    val deviceType: String? = null,
    val deviceSize: String? = null, // "mobile" | "tablet" | "desktop"
    val sdkVersion: String? = null,
    val appVersion: String? = null,
    val app: String? = null,
    val deviceLanguage: String? = null,
    val userLanguage: String? = null,
    val countryCode: String? = null,
    val preferredTheme: String? = null,
    val timezone: String? = null,
    val urlOrScreenName: String? = null,
)

data class ShowFormResponse(
    val feedbackConfigurationId: String,
    val feedbackIdentifier: String? = null,
    val triggerType: TriggerType? = null,
    val formConfiguration: Map<String, JsonElement>? = null,
    val questionnaireFields: JsonElement? = null,
    val otherConfigurationProperties: JsonElement? = null,
    val appearanceProperties: JsonElement? = null,
    val partialResponseEnabled: Boolean? = null,
    val contact: Map<String, JsonElement>? = null,
    val projectI18nFileUrl: String? = null,
    val pingAgainIn: Double? = null,
    val pingOnNextPageVisit: Boolean? = null,
    val feedbackTransactions: String? = null,
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

// @Serializable is load-bearing: ApiClient.streamQnaWithAi serializes the conversation via a
// runtime serializer<List<...>>() lookup, which fails at runtime (not compile time) without it.
@Serializable
data class QnaWithAiConversationTurn(
    val question: String,
    val answer: String,
)

data class QnaWithAiRequest(
    val feedbackConfigurationId: String,
    val questionId: String,
    val conversation: List<QnaWithAiConversationTurn> = emptyList(),
)

data class QnaWithAiResponse(
    val answer: String,
)

/** A local file reference to upload — either raw bytes or a content URI resolved by the host app. */
sealed class UploadFileSource {
    data class Bytes(val bytes: ByteArray, val mimeType: String? = null) : UploadFileSource()
    data class ContentUri(val uri: String, val mimeType: String? = null) : UploadFileSource()
}

data class UploadFileRequest(
    val feedbackConfigurationId: String,
    val questionId: String,
    val file: UploadFileSource,
    val fileName: String = "upload",
    val onProgress: ((Int) -> Unit)? = null,
)

data class UploadFileResponse(
    val fileUrl: String,
)

enum class QuestionType(val wireValue: String) {
    RATING("rating"),
    SINGLE_CHOICE("single_choice"),
    NPS("nps"),
    NESTED_SELECTION("nested_selection"),
    MULTIPLE_CHOICE_MULTIPLE("multiple_choice_multiple"),
    SHORT_ANSWER("short_answer"),
    LONG_TEXT("long_text"),
    ANNOTATION("annotation"),
    WELCOME("welcome"),
    THANK_YOU("thank_you"),
    MESSAGE_PANEL("message_panel"),
    YES_NO("yes_no"),
    RATING_MATRIX("rating_matrix"),
    MATRIX_SINGLE_CHOICE("matrix_single_choice"),
    MATRIX_MULTIPLE_CHOICE("matrix_multiple_choice"),
    EXIT_FORM("exit_form"),
    CONSENT("consent"),
    DATE("date"),
    CSAT("csat"),
    OPINION_SCALE("opinion_scale"),
    RANKING("ranking"),
    PICTURE_CHOICE("picture_choice"),
    SIGNATURE("signature"),
    FILE_UPLOAD("file_upload"),
    EMAIL("email"),
    NUMBER("number"),
    WEBSITE("website"),
    PHONE_NUMBER("phone_number"),
    ADDRESS("address"),
    VIDEO_AUDIO("video_audio"),
    SCHEDULER("scheduler"),
    QNA_WITH_AI("qna_with_ai"),
    PAYMENTS_UPI("payments_upi");

    companion object {
        fun fromWire(value: String): QuestionType? = entries.find { it.wireValue == value }
    }
}
