package com.encatch.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Helpers for custom native forms (when using [EncatchConfig.onBeforeShowForm]). Use these to
 * build the JSON string [Encatch.submitForm] expects from your own native form's responses,
 * mirroring `:core`'s `FormSubmitBuilder.kt` (which mirrors `form-helpers.ts`'s
 * `buildSubmitRequest`) — ported here rather than reused directly since `:kmp-sdk`'s `commonMain`
 * can't depend on `:core` (see [Encatch.submitForm]'s doc comment).
 */

/** Same wire-format Json config as `:core`'s `EncatchJson`, kept in lockstep by hand. */
internal val EncatchSdkJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

/**
 * A single native-form answer to convert. [value]'s expected shape depends on [type] — see
 * `:core`'s `FormSubmitBuilder.kt` doc comment for the full per-type mapping (numeric scales take
 * [Number]/numeric [String]; text types take [String]; choice/ranking types take [String] or
 * `List<String>`; boolean types take [Boolean] or `"true"`/`1`; display-only types ignore [value]).
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
)

/** Converts a plain Kotlin value (String/Number/Boolean/List/Map/null) into a [JsonElement]. */
@Suppress("UNCHECKED_CAST")
private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is List<*> -> buildJsonArray { value.forEach { add(anyToJsonElement(it)) } }
    is Map<*, *> -> buildJsonObject { value.forEach { (k, v) -> put(k.toString(), anyToJsonElement(v)) } }
    else -> JsonPrimitive(value.toString())
}

/**
 * Maps a native form question type + value to the [Answer] format. Covers all 33 question types
 * defined in the schema. Unknown types are stored as `shortAnswer` for forward-compatibility.
 */
@Suppress("UNCHECKED_CAST")
fun toQuestionAnswer(type: String, value: Any?): Answer {
    fun toNum(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        else -> v?.toString()?.toDoubleOrNull() ?: 0.0
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
    fun toRatingMatrix(v: Any?): Map<String, JsonElement> =
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
 * Builds the JSON string [Encatch.submitForm] expects, from native form responses. Use when you
 * have a custom native form (shown after [EncatchConfig.onBeforeShowForm] returns `false`) and
 * need to submit to the Encatch API.
 *
 * Example:
 * ```kotlin
 * val responses = listOf(
 *     NativeFormResponse("q1", "rating", 5),
 *     NativeFormResponse("q2", "short_answer", "Great product!"),
 * )
 * val requestJson = buildSubmitRequest(
 *     BuildSubmitRequestOptions(formConfigurationId = payload.formId),
 *     responses,
 * )
 * Encatch.submitForm(requestJson)
 * ```
 */
fun buildSubmitRequest(options: BuildSubmitRequestOptions, responses: List<NativeFormResponse>): String {
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
    )

    val request = SubmitFormRequest(
        triggerType = options.triggerType,
        formDetails = formDetails,
    )

    return EncatchSdkJson.encodeToString(SubmitFormRequest.serializer(), request)
}
