package com.encatch.core

import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject

/**
 * Helpers for custom native forms (when using the [BeforeShowFormInterceptor]).
 * Use these to build a [SubmitFormRequest] from your native form responses,
 * mirroring `form-helpers.ts`'s `buildSubmitRequest`.
 */

/**
 * A single native-form answer to convert. [value]'s expected shape depends on [type]:
 * - Numeric scales (rating/nps/csat/opinionScale): [Number] or a numeric [String].
 * - Text (shortAnswer/longText/email/number/website/date): [String] (or anything — coerced via `toString()`).
 * - Single/multi choice, ranking, picture choice, nestedSelection: [String] or `List<String>`.
 * - Boolean (yesNo/consent): [Boolean], or `"true"`/`1` (matches the RN helper's loose coercion).
 * - Matrix types: `Map<String, *>` (ratingMatrix values may be [Number] or [String]).
 * - Complex structured types: pass the matching type directly — [SignatureAnswer], `List<FileUploadAnswerItem>`,
 *   [PhoneNumberAnswer], [AddressAnswer], [VideoAudioAnswer], [SchedulerAnswer], `List<QnaWithAiPair>`,
 *   [PaymentsUpiAnswer], [Annotation].
 * - Display-only types (welcome/thank_you/message_panel/exit_form): [value] is ignored.
 */
data class NativeFormResponse(
    val questionId: String,
    val type: String,
    val value: Any?,
)

data class BuildSubmitRequestOptions(
    val formConfigurationId: String,
    val triggerType: String = "manual",
    val responseLanguageCode: String? = null,
    val completionTimeInSeconds: Int? = null,
    val isPartialSubmit: Boolean? = null,
    val feedbackIdentifier: String? = null,
    /** Arbitrary caller-provided metadata attached to this submission. */
    val context: JsonObject? = null,
)

/**
 * Maps a native form question type + value to the [Answer] format. Covers all 33 question
 * types defined in the schema. Unknown types are stored as `shortAnswer` for
 * forward-compatibility, matching the RN helper's fallback.
 */
@Suppress("UNCHECKED_CAST")
fun toQuestionAnswer(type: String, value: Any?): Answer {
    // Int result (rounded): the backend's scale-answer fields (rating/nps/csat/opinionScale)
    // are i32 — a float on the wire is a 422.
    fun toNum(v: Any?): Int = when (v) {
        is Number -> v.toDouble().roundToInt()
        else -> v?.toString()?.toDoubleOrNull()?.roundToInt() ?: 0
    }
    fun toStr(v: Any?): String = v?.toString() ?: ""
    fun toStrList(v: Any?): List<String> = when (v) {
        is List<*> -> v.map { it.toString() }
        else -> listOf(toStr(v))
    }
    fun toBool(v: Any?): Boolean = v == true || v == "true" || v == 1 || v == 1.0
    fun toStringMap(v: Any?): Map<String, String> =
        (v as? Map<*, *>)?.entries?.associate { (k, mv) -> k.toString() to mv.toString() } ?: emptyMap()
    fun toStringListMap(v: Any?): Map<String, List<String>> =
        (v as? Map<*, *>)?.entries?.associate { (k, mv) -> k.toString() to toStrList(mv) } ?: emptyMap()
    fun toRatingMatrix(v: Any?): Map<String, kotlinx.serialization.json.JsonElement> =
        (v as? Map<*, *>)?.entries?.associate { (k, mv) -> k.toString() to anyToJsonElement(mv) } ?: emptyMap()

    return when (QuestionType.fromWire(type)) {
        QuestionType.RATING -> Answer(rating = toNum(value))
        QuestionType.NPS -> Answer(nps = toNum(value))
        QuestionType.CSAT -> Answer(csat = toNum(value))
        QuestionType.OPINION_SCALE -> Answer(opinionScale = toNum(value))

        QuestionType.SHORT_ANSWER -> Answer(shortAnswer = toStr(value))
        QuestionType.LONG_TEXT -> Answer(longText = toStr(value))
        QuestionType.EMAIL -> Answer(email = toStr(value))
        QuestionType.NUMBER -> Answer(number = toStr(value))
        QuestionType.WEBSITE -> Answer(website = toStr(value))

        QuestionType.SINGLE_CHOICE -> Answer(singleChoice = toStr(value))
        QuestionType.MULTIPLE_CHOICE_MULTIPLE -> Answer(multipleChoiceMultiple = toStrList(value))
        QuestionType.PICTURE_CHOICE -> Answer(pictureChoice = toStrList(value))
        QuestionType.RANKING -> Answer(ranking = toStrList(value))

        QuestionType.YES_NO -> Answer(yesNo = toBool(value))
        QuestionType.CONSENT -> Answer(consent = toBool(value))

        QuestionType.DATE -> Answer(date = toStr(value))

        QuestionType.RATING_MATRIX -> Answer(ratingMatrix = toRatingMatrix(value))
        QuestionType.MATRIX_SINGLE_CHOICE -> Answer(matrixSingleChoice = toStringMap(value))
        QuestionType.MATRIX_MULTIPLE_CHOICE -> Answer(matrixMultipleChoice = toStringListMap(value))

        QuestionType.NESTED_SELECTION -> Answer(nestedSelection = toStrList(value))

        QuestionType.ANNOTATION -> Answer(annotation = value as? Annotation)

        QuestionType.SIGNATURE -> Answer(signature = value as? SignatureAnswer)
        QuestionType.FILE_UPLOAD -> Answer(fileUpload = value as? List<FileUploadAnswerItem>)
        QuestionType.PHONE_NUMBER -> Answer(phoneNumber = value as? PhoneNumberAnswer)
        QuestionType.ADDRESS -> Answer(address = value as? AddressAnswer)
        QuestionType.VIDEO_AUDIO -> Answer(videoAudio = value as? VideoAudioAnswer)
        QuestionType.SCHEDULER -> Answer(scheduler = value as? SchedulerAnswer)
        QuestionType.QNA_WITH_AI -> Answer(qnaWithAi = value as? List<QnaWithAiPair>)
        QuestionType.PAYMENTS_UPI -> Answer(paymentsUpi = value as? PaymentsUpiAnswer)

        QuestionType.WELCOME, QuestionType.THANK_YOU, QuestionType.MESSAGE_PANEL, QuestionType.EXIT_FORM -> Answer()

        null -> Answer(shortAnswer = toStr(value))
    }
}

/**
 * Builds a [SubmitFormRequest] from native form responses. Use when you have a custom native
 * form (shown after [Encatch]'s `onBeforeShowForm` interceptor returns `false`) and need to
 * submit to the Encatch API.
 *
 * Example:
 * ```kotlin
 * val responses = listOf(
 *     NativeFormResponse("q1", "rating", 5),
 *     NativeFormResponse("q2", "short_answer", "Great product!"),
 * )
 * val request = buildSubmitRequest(
 *     BuildSubmitRequestOptions(formConfigurationId = formConfig.feedbackConfigurationId),
 *     responses,
 * )
 * Encatch.submitForm(request)
 * ```
 */
fun buildSubmitRequest(options: BuildSubmitRequestOptions, responses: List<NativeFormResponse>): SubmitFormRequest {
    val questions = responses.map { r ->
        QuestionResponse(
            questionId = r.questionId,
            type = r.type,
            answer = toQuestionAnswer(r.type, r.value),
        )
    }

    val formDetails = FormDetails(
        formConfigurationId = options.formConfigurationId,
        responseLanguageCode = options.responseLanguageCode,
        completionTimeInSeconds = options.completionTimeInSeconds,
        isPartialSubmit = options.isPartialSubmit,
        feedbackIdentifier = options.feedbackIdentifier,
        response = FormResponse(questions = questions),
        context = options.context,
    )

    return SubmitFormRequest(
        triggerType = options.triggerType,
        formDetails = formDetails,
    )
}
