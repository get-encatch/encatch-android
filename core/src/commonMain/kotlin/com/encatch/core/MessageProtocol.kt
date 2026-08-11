package com.encatch.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Messages emitted by the hosted form page (WebView) to the native SDK. */
enum class FormMessageType(val wireValue: String) {
    READY("form:ready"),
    SUBMIT("form:submit"),
    COMPLETE("form:complete"),
    CLOSE("form:close"),
    ERROR("form:error"),
    RESIZE("form:resize"),
    LAYOUT("form:layout"),
    CLOSE_BUTTON("form:closeButton"),
    THEME_DATA("form:themeData"),
    REFINE_TEXT_REQUEST("form:refineTextRequest"),
    STARTED("form:started"),
    ANSWERED("form:answered"),
    SECTION_CHANGE("form:section:change"),
    SHOW("form:show"),
    READY_TO_DISMISS("form:readyToDismiss"),
    UPLOAD_FILE_REQUEST("form:uploadFileRequest"),
    QNA_WITH_AI_REQUEST("form:qnaWithAiRequest"),
    REMIND_ME_LATER("form:remindmelater"),
    CTA_TRIGGERED("form:ctaTriggered");

    companion object {
        fun fromWire(value: String): FormMessageType? = entries.find { it.wireValue == value }
    }
}

/** Envelope for a message received from the WebView, parsed via [kotlinx.serialization]. */
@Serializable
data class FormMessage(
    val type: String,
    val formId: String? = null,
    val data: JsonObject? = null,
) {
    val messageType: FormMessageType? get() = FormMessageType.fromWire(type)
}

/** Messages sent from the native SDK to the hosted form page (WebView), via evaluateJavascript. */
enum class SDKMessageType(val wireValue: String) {
    FORM_CONFIG("sdk:formConfig"),
    THEME("sdk:theme"),
    LOCALE("sdk:locale"),
    RESET_DATA("sdk:resetData"),
    PREFILL_RESPONSES("sdk:prefillResponses"),
    REFINE_TEXT_RESPONSE("sdk:refineTextResponse"),
    SUBMIT_PARTIAL_BEFORE_DISMISS("sdk:submitPartialBeforeDismiss"),
    UPLOAD_FILE_RESPONSE("sdk:uploadFileResponse"),
    UPLOAD_FILE_PROGRESS("sdk:uploadFileProgress"),
    QNA_WITH_AI_RESPONSE("sdk:qnaWithAiResponse"),
    QNA_WITH_AI_CHUNK("sdk:qnaWithAiChunk"),
    QNA_WITH_AI_DONE("sdk:qnaWithAiDone"),
}

/** Envelope for a message sent to the WebView; [dataJson] is a pre-serialized JSON object string, or null. */
data class SDKMessage(
    val type: SDKMessageType,
    val dataJson: String? = null,
)

/** Wire-format for a deferred exit_form CTA action, e.g. `redirect_internal`/`redirect_external`/`app_navigate`. */
object CtaAction {
    const val DISMISS = "dismiss"
    const val APP_NAVIGATE = "app_navigate"
    const val REDIRECT_INTERNAL = "redirect_internal"
    const val REDIRECT_EXTERNAL = "redirect_external"
}
