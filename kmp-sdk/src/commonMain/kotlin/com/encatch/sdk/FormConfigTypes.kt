package com.encatch.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Typed form-configuration types, ported verbatim from `:core`'s
 * `core/src/commonMain/kotlin/com/encatch/core/Types.kt` — pure Kotlin types with no `:core`
 * dependency, for the same reason `FormSubmitTypes.kt` carries its own copy of the submit tree
 * (see its file-level doc comment: `:core` has no iOS target, so `:kmp-sdk`'s `commonMain`
 * cannot depend on it). Keep in lockstep with `:core`'s copy by hand. All shapes mirror
 * `@encatch/schema` 1.5.2 (`fetch-feedback-schema.ts`, `completion-cta-schema.ts`).
 */

/** Form title/description metadata returned by fetch-feedback APIs. */
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
 * A single logic-jump rule evaluated during form navigation. Kept high-level: [jsonLogic] is
 * the raw JSON Logic expression, not modeled further.
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
 * Per-surface completion CTA action (in-app vs shareable link) — the *static config* shape on
 * thank_you/exit_form questions, distinct from the runtime `pendingCompletionCta` payload the
 * web engine hands back after submit.
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

/** Completion CTA configuration for thank_you and exit_form questions. */
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

private val formConfigJsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Parses [ShowFormInterceptorPayload.formConfigJson]'s `formConfiguration` object into the typed
 * fetch-feedback shape, or `null` when the passthrough JSON is absent or unparseable.
 */
fun ShowFormInterceptorPayload.typedFormConfiguration(): FormConfigurationResponse? {
    val raw = formConfigJson ?: return null
    val root = runCatching { formConfigJsonParser.parseToJsonElement(raw) }.getOrNull() as? JsonObject ?: return null
    return FormConfigurationResponse.fromJson(root["formConfiguration"] as? JsonObject)
}
