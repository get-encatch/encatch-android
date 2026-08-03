package com.encatch.sdk

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format submit-request types, ported verbatim from `:core`'s
 * `core/src/commonMain/kotlin/com/encatch/core/Answer.kt` (and its `QuestionType` sibling in
 * `Types.kt`) — pure Kotlin/`kotlinx.serialization` types with no `:core` dependency, so they can
 * live directly in `:kmp-sdk`'s `commonMain`. See [Encatch.submitForm]'s doc comment for why
 * `:kmp-sdk` can't depend on `:core` at all (no iOS target) and therefore needs its own copy of
 * this tree rather than importing `com.encatch.core.SubmitFormRequest` directly. Field names are
 * the exact JSON keys sent to the Encatch backend — keep in lockstep with `:core`'s copy by hand.
 */

@Serializable
data class AnnotationMarker(
    val markerNo: String,
    val timeline: String,
    val comment: String,
)

@Serializable
data class Annotation(
    val fileType: String,
    val fileName: String,
    val markers: List<AnnotationMarker> = emptyList(),
)

/** mode: "type" | "draw" | "upload" */
@Serializable
data class SignatureAnswer(
    val mode: String,
    val fileUrl: String? = null,
    val typedName: String? = null,
)

@Serializable
data class FileUploadAnswerItem(
    val fileUrl: String,
    val fileName: String,
    val fileSizeMb: Double,
    val mimeType: String? = null,
)

@Serializable
data class PhoneNumberAnswer(
    val countryCode: String,
    val number: String,
    val e164: String? = null,
)

@Serializable
data class AddressAnswer(
    val addressLine1: String? = null,
    val addressLine2: String? = null,
    val city: String? = null,
    val stateProvince: String? = null,
    val postalCode: String? = null,
    val country: String? = null,
)

/** mode: "video" | "audio" | "photo" | "text" */
@Serializable
data class VideoAudioAnswer(
    val mode: String,
    val fileUrl: String? = null,
    val text: String? = null,
    val durationSeconds: Double? = null,
    val transcriptText: String? = null,
)

/** provider: "google_calendar" | "calendly" — discriminated union, matches the schema's z.discriminatedUnion. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("provider")
sealed class SchedulerAnswer {
    abstract val bookedAt: String

    @Serializable
    @SerialName("google_calendar")
    data class GoogleCalendar(
        override val bookedAt: String,
    ) : SchedulerAnswer()

    @Serializable
    @SerialName("calendly")
    data class Calendly(
        val slotStart: String,
        val slotEnd: String,
        val eventId: String? = null,
        override val bookedAt: String,
    ) : SchedulerAnswer()
}

@Serializable
data class QnaWithAiPair(
    val question: String,
    val answer: String,
)

@Serializable
data class PaymentsUpiAnswer(
    val transactionId: String,
    val encatchPaymentReference: String,
    val amount: String,
    val currency: String = "INR",
    val payeeVpa: String,
    val payeeName: String? = null,
    val sourceEmail: String? = null,
    val upiIntentUri: String? = null,
    val selfReported: Boolean = true,
)

/**
 * Flexible answer item — matches `AnswerItemSchema`. Only the field(s) matching the question's
 * [QuestionType] are populated. `ratingMatrix` values are number-or-string per the schema, modeled
 * as [JsonElement].
 */
@Serializable
data class Answer(
    val nps: Double? = null,
    val nestedSelection: List<String>? = null,
    val longText: String? = null,
    val shortAnswer: String? = null,
    val singleChoice: String? = null,
    val rating: Double? = null,
    val yesNo: Boolean? = null,
    val consent: Boolean? = null,
    val multipleChoiceMultiple: List<String>? = null,
    val singleChoiceOther: String? = null,
    val multipleChoiceOther: String? = null,
    val annotation: Annotation? = null,
    val ratingMatrix: Map<String, JsonElement>? = null,
    val matrixSingleChoice: Map<String, String>? = null,
    val matrixMultipleChoice: Map<String, List<String>>? = null,
    val others: String? = null,
    val date: String? = null,
    val csat: Double? = null,
    val opinionScale: Double? = null,
    val ranking: List<String>? = null,
    val pictureChoice: List<String>? = null,
    val pictureChoiceOther: String? = null,
    val signature: SignatureAnswer? = null,
    val fileUpload: List<FileUploadAnswerItem>? = null,
    val email: String? = null,
    /** Submitted numeric value as a string, matching the schema. */
    val number: String? = null,
    val website: String? = null,
    val phoneNumber: PhoneNumberAnswer? = null,
    val address: AddressAnswer? = null,
    val videoAudio: VideoAudioAnswer? = null,
    val scheduler: SchedulerAnswer? = null,
    val qnaWithAi: List<QnaWithAiPair>? = null,
    val paymentsUpi: PaymentsUpiAnswer? = null,
)

@Serializable
data class QuestionResponse(
    val questionId: String,
    val answer: Answer? = null,
    val type: String? = null,
    val error: String? = null,
    val isOnPath: Boolean? = null,
    val timeSpentMs: Long? = null,
    val isPathTraversed: Boolean? = null,
)

@Serializable
data class FormResponse(
    val questions: List<QuestionResponse>? = null,
    val context: JsonObject? = null,
    val contact: JsonObject? = null,
    val sourceTrackingFieldValues: Map<String, String>? = null,
)

@Serializable
data class FormDetails(
    val formConfigurationId: String,
    val feedbackIdentifier: String? = null,
    val responseLanguageCode: String? = null,
    val isPartialSubmit: Boolean? = null,
    val completionTimeInSeconds: Double? = null,
    val response: FormResponse? = null,
    val visitedQuestionIds: List<String>? = null,
    val context: JsonObject? = null,
)

@Serializable
data class SubmitFormRequest(
    val triggerType: String? = null, // "automatic" | "manual"
    val formDetails: FormDetails,
)

/** Mirrors `:core`'s `QuestionType` enum (`Types.kt`) — the 33 question type wire values. */
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
