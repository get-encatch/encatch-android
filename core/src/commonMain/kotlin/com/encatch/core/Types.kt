package com.encatch.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

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

/**
 * Form title/description metadata returned by fetch-feedback APIs — mirrors
 * `@encatch/schema`'s `formConfigurationResponseSchema` (`fetch-feedback-schema.ts`).
 */
data class FormConfigurationResponse(
    val formTitle: String = "",
    val formDescription: String = "",
    /** Server-enriched total respondent count, used for the welcome badge. */
    val respondentsCount: Int? = null,
) {
    companion object {
        /** Defensive parse — unknown/missing fields fall back to defaults, never throws. */
        fun fromJson(json: JsonObject?): FormConfigurationResponse? {
            if (json == null) return null
            return FormConfigurationResponse(
                formTitle = (json["formTitle"] as? JsonPrimitive)?.contentOrNull ?: "",
                formDescription = (json["formDescription"] as? JsonPrimitive)?.contentOrNull ?: "",
                respondentsCount = (json["respondentsCount"] as? JsonPrimitive)?.intOrNull,
            )
        }
    }
}

/**
 * A single logic-jump rule evaluated during form navigation — mirrors
 * `@encatch/schema`'s `logicJumpRuleSchema`. Kept high-level: [jsonLogic] is the raw
 * JSON Logic expression, not modeled further.
 */
data class LogicJumpRule(
    val jsonLogic: JsonObject = JsonObject(emptyMap()),
    val targetQuestionId: String = "",
) {
    companion object {
        fun fromJson(json: JsonObject?): LogicJumpRule? {
            if (json == null) return null
            return LogicJumpRule(
                jsonLogic = json["jsonLogic"] as? JsonObject ?: JsonObject(emptyMap()),
                targetQuestionId = (json["targetQuestionId"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }
    }
}

/** Supported completion CTA actions on thank_you and exit_form screens. */
enum class CompletionCtaAction(val wireValue: String) {
    DISMISS("dismiss"),
    APP_NAVIGATE("app_navigate"),
    REDIRECT_INTERNAL("redirect_internal"),
    REDIRECT_EXTERNAL("redirect_external");

    companion object {
        fun fromWire(value: String?): CompletionCtaAction? = entries.find { it.wireValue == value }
    }
}

/**
 * Per-surface completion CTA action (in-app vs shareable link) — mirrors
 * `@encatch/schema`'s `platformCompletionCtaSchema`. This is the *static config* shape on
 * thank_you/exit_form questions; the runtime wire payload the web engine hands back after
 * submit is [PendingCompletionCta].
 */
data class PlatformCompletionCta(
    val action: CompletionCtaAction,
    /** App-specific route for [CompletionCtaAction.APP_NAVIGATE]. */
    val route: String? = null,
    /** Target URL for the redirect actions. */
    val url: String? = null,
) {
    companion object {
        /** Defensive parse — an unknown action falls back to [CompletionCtaAction.DISMISS]. */
        fun fromJson(json: JsonObject?): PlatformCompletionCta? {
            if (json == null) return null
            return PlatformCompletionCta(
                action = CompletionCtaAction.fromWire((json["action"] as? JsonPrimitive)?.contentOrNull)
                    ?: CompletionCtaAction.DISMISS,
                route = (json["route"] as? JsonPrimitive)?.contentOrNull,
                url = (json["url"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

/** Optional secondary button on thank_you completion CTAs; label-only configs default to dismiss. */
data class CompletionCtaSecondary(
    val label: String,
    val inApp: PlatformCompletionCta? = null,
    val link: PlatformCompletionCta? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): CompletionCtaSecondary? {
            if (json == null) return null
            return CompletionCtaSecondary(
                label = (json["label"] as? JsonPrimitive)?.contentOrNull ?: "",
                inApp = PlatformCompletionCta.fromJson(json["inApp"] as? JsonObject),
                link = PlatformCompletionCta.fromJson(json["link"] as? JsonObject),
            )
        }
    }
}

/**
 * Completion CTA configuration for thank_you and exit_form questions — mirrors
 * `@encatch/schema`'s `completionCtaSchema` (`completion-cta-schema.ts`).
 */
data class CompletionCta(
    val label: String? = null,
    /** When set, auto-fires the primary action after this many milliseconds. */
    val autoTriggerDelayMs: Long? = null,
    val inApp: PlatformCompletionCta? = null,
    val link: PlatformCompletionCta? = null,
    val secondary: CompletionCtaSecondary? = null,
) {
    companion object {
        fun fromJson(json: JsonObject?): CompletionCta? {
            if (json == null) return null
            return CompletionCta(
                label = (json["label"] as? JsonPrimitive)?.contentOrNull,
                autoTriggerDelayMs = (json["autoTriggerDelayMs"] as? JsonPrimitive)?.longOrNull,
                inApp = PlatformCompletionCta.fromJson(json["inApp"] as? JsonObject),
                link = PlatformCompletionCta.fromJson(json["link"] as? JsonObject),
                secondary = CompletionCtaSecondary.fromJson(json["secondary"] as? JsonObject),
            )
        }
    }
}

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
) {
    /** Parses [formConfiguration] into the typed fetch-feedback shape, or `null` when absent. */
    val typedFormConfiguration: FormConfigurationResponse?
        get() = FormConfigurationResponse.fromJson(formConfiguration?.let { JsonObject(it) })
}

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

    @Deprecated("The annotation question type is deprecated. Kept for backward compatibility with existing configurations.")
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

    @Deprecated("The payments_upi question type is slated for removal. Kept for backward compatibility with existing configurations.")
    PAYMENTS_UPI("payments_upi");

    companion object {
        fun fromWire(value: String): QuestionType? = entries.find { it.wireValue == value }
    }
}
