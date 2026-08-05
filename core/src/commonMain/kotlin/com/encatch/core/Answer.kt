package com.encatch.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire-format answer types, ported 1:1 from `@encatch/schema`'s
 * `answer-schema.ts` (the source of truth shared by web/RN/Android SDKs).
 * Field names are the exact JSON keys sent to the Encatch backend.
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
 * Flexible answer item — matches `AnswerItemSchema`. Only the field(s) matching the
 * question's [QuestionType] are populated. `ratingMatrix` values are number-or-string
 * per the schema (`z.union([z.number(), z.string()])`), modeled as [JsonElement].
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
    // Int, not Double: the backend deserializes this as i32 and 422s on "1.0".
    val completionTimeInSeconds: Int? = null,
    val response: FormResponse? = null,
    val visitedQuestionIds: List<String>? = null,
    val context: JsonObject? = null,
)

@Serializable
data class SubmitFormRequest(
    val triggerType: String? = null, // "automatic" | "manual"
    val formDetails: FormDetails,
)
