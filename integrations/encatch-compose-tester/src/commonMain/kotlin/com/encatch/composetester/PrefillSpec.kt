package com.encatch.composetester

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * addToResponse question types, sample values, and parsing — ported from the web tester's
 * `add-to-response-types.ts` (slash-admin-encatch) so all tester apps drive prefill the same
 * way. Panel types (welcome, thank_you, message_panel, exit_form) are excluded — no answer to
 * prefill. Kept in lockstep by hand with the copies in the other tester apps.
 */

enum class PrefillEditor { BOOL, NUMBER, TEXT, LONG_TEXT, JSON }

data class PrefillQuestionType(
    val wire: String,
    val label: String,
    val editor: PrefillEditor,
    /** Canonical sample value (JSON text for JSON editors, plain text otherwise). */
    val sample: String,
    val hint: String,
)

data class PrefillCategory(val label: String, val types: List<PrefillQuestionType>)

private fun t(wire: String, label: String, editor: PrefillEditor, sample: String, hint: String) =
    PrefillQuestionType(wire, label, editor, sample, hint)

val PREFILL_CATEGORIES: List<PrefillCategory> = listOf(
    PrefillCategory(
        "Scale",
        listOf(
            t("rating", "Rating", PrefillEditor.NUMBER, "4", "Number 1–5 (or form max rating)"),
            t("csat", "CSAT", PrefillEditor.NUMBER, "4", "Number 1–5 (scale size depends on form)"),
            t("nps", "NPS", PrefillEditor.NUMBER, "9", "Number 0–10"),
            t("opinion_scale", "Opinion scale", PrefillEditor.NUMBER, "7", "Numeric scale value"),
        ),
    ),
    PrefillCategory(
        "Choice",
        listOf(
            t("single_choice", "Single choice", PrefillEditor.TEXT, "option_1", "Option value string from the form schema"),
            t("yes_no", "Yes / No", PrefillEditor.BOOL, "true", "true = Yes, false = No"),
            t("nested_selection", "Nested selection", PrefillEditor.JSON, """["category_a", "sub_option_1"]""", "JSON array of option values"),
            t("picture_choice", "Picture choice", PrefillEditor.JSON, """["picture_option_1"]""", "JSON array of option values"),
            t("multiple_choice_multiple", "Multiple choice", PrefillEditor.JSON, """["option_a", "option_b"]""", "JSON array of option values"),
            t("consent", "Consent", PrefillEditor.BOOL, "true", "true = agreed, false = not agreed"),
            t("ranking", "Ranking", PrefillEditor.JSON, """["option_a", "option_b", "option_c"]""", "JSON array in ranked order"),
        ),
    ),
    PrefillCategory(
        "Matrix",
        listOf(
            t("rating_matrix", "Rating matrix", PrefillEditor.JSON, """{"statement_1": 4, "statement_2": 5}""", "JSON object: row id -> rating"),
            t("matrix_single_choice", "Matrix (single per row)", PrefillEditor.JSON, """{"row_1": "column_a", "row_2": "column_b"}""", "JSON object: row id -> column id"),
            t("matrix_multiple_choice", "Matrix (multiple per row)", PrefillEditor.JSON, """{"row_1": ["column_a"], "row_2": ["column_a", "column_b"]}""", "JSON object: row id -> column ids"),
        ),
    ),
    PrefillCategory(
        "Text",
        listOf(
            t("short_answer", "Short answer", PrefillEditor.TEXT, "Sample short answer", "Plain text value"),
            t("long_text", "Long answer", PrefillEditor.LONG_TEXT, "Sample long answer text for testing addToResponse.", "Long text value"),
            t("date", "Date", PrefillEditor.TEXT, "2024-06-15", "Plain text value (YYYY-MM-DD)"),
            t("number", "Number", PrefillEditor.TEXT, "42", "Plain text value (numeric string)"),
        ),
    ),
    PrefillCategory(
        "Contact info",
        listOf(
            t("email", "Email", PrefillEditor.TEXT, "test@example.com", "Plain text value"),
            t(
                "phone_number", "Phone number", PrefillEditor.JSON,
                """{"countryCode": "+1", "number": "5551234567", "e164": "+15551234567"}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t("website", "Website", PrefillEditor.TEXT, "https://example.com", "Plain text value"),
            t(
                "address", "Address", PrefillEditor.JSON,
                """{"addressLine1": "123 Main St", "city": "San Francisco", "stateProvince": "CA", "postalCode": "94105", "country": "US"}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "signature", "Signature", PrefillEditor.JSON,
                """{"mode": "type", "typedName": "Jane Doe"}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
        ),
    ),
    PrefillCategory(
        "Advanced",
        listOf(
            t(
                "file_upload", "File upload", PrefillEditor.JSON,
                """[{"fileUrl": "https://example.com/uploads/sample.pdf", "fileName": "sample.pdf", "fileSizeMb": 0.5, "mimeType": "application/pdf"}]""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "video_audio", "Video / audio / photo", PrefillEditor.JSON,
                """{"mode": "text", "text": "Sample video/audio text response"}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "scheduler", "Scheduler", PrefillEditor.JSON,
                """{"provider": "google_calendar", "bookedAt": "1710000000"}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "qna_with_ai", "Q&A with AI", PrefillEditor.JSON,
                """[{"question": "What is your return policy?", "answer": "Returns are accepted within 30 days."}]""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "annotation", "Annotation", PrefillEditor.JSON,
                """{"fileType": "video/mp4", "fileName": "demo.mp4", "markers": [{"markerNo": "1", "timeline": "00:01:30", "comment": "Issue here"}]}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
            t(
                "payments_upi", "Payments UPI", PrefillEditor.JSON,
                """{"transactionId": "123456789012", "encatchPaymentReference": "enc_ref_sample_001", "amount": "99.00", "currency": "INR", "payeeVpa": "merchant@upi", "payeeName": "Sample Merchant", "selfReported": true}""",
                "JSON matching the answer shape in @encatch/schema",
            ),
        ),
    ),
)

val ALL_PREFILL_TYPES: List<PrefillQuestionType> = PREFILL_CATEGORIES.flatMap { it.types }

fun prefillTypeByWire(wire: String): PrefillQuestionType =
    ALL_PREFILL_TYPES.find { it.wire == wire } ?: ALL_PREFILL_TYPES.first { it.wire == "short_answer" }

/** One editable prefill row: which question to answer, its type, and the raw value text. */
@Serializable
data class PrefillRow(
    val questionId: String = "",
    val typeWire: String = "short_answer",
    val value: String = "",
) {
    val type: PrefillQuestionType get() = prefillTypeByWire(typeWire)
}

private val prefillJson = Json { ignoreUnknownKeys = true }

fun encodePrefillRows(rows: List<PrefillRow>): String =
    prefillJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(PrefillRow.serializer()), rows)

fun decodePrefillRows(raw: String?): List<PrefillRow> =
    raw?.let {
        runCatching {
            prefillJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PrefillRow.serializer()), it)
        }.getOrNull()
    } ?: emptyList()

/**
 * Parses a row's raw value text into the value passed to `addToResponse` — strict per editor
 * kind, mirroring the web tester's `parseAddToResponseValue`. Throws [IllegalArgumentException]
 * with a readable message on invalid input.
 */
fun parsePrefillValue(type: PrefillQuestionType, raw: String): Any? {
    val trimmed = raw.trim()
    return when (type.editor) {
        PrefillEditor.BOOL -> when (trimmed) {
            "true" -> true
            "false" -> false
            else -> throw IllegalArgumentException("Expected true or false for ${type.label}")
        }
        PrefillEditor.NUMBER -> trimmed.toIntOrNull()
            ?: throw IllegalArgumentException("Expected a whole number for ${type.label}")
        PrefillEditor.JSON -> runCatching { prefillJson.parseToJsonElement(trimmed) }.getOrElse {
            throw IllegalArgumentException("Invalid JSON for ${type.label}")
        }
        PrefillEditor.TEXT, PrefillEditor.LONG_TEXT -> raw
    }
}
