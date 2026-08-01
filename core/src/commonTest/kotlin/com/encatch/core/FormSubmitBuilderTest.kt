package com.encatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormSubmitBuilderTest {

    @Test
    fun toQuestionAnswer_numericScales_coerceToDouble() {
        assertEquals(5.0, toQuestionAnswer("rating", 5).rating)
        assertEquals(9.0, toQuestionAnswer("nps", "9").nps)
        assertEquals(4.0, toQuestionAnswer("csat", 4.0).csat)
    }

    @Test
    fun toQuestionAnswer_text_coercesToString() {
        assertEquals("Great!", toQuestionAnswer("short_answer", "Great!").shortAnswer)
        assertEquals("42", toQuestionAnswer("number", 42).number)
    }

    @Test
    fun toQuestionAnswer_multipleChoice_acceptsListOrSingleValue() {
        assertEquals(listOf("a", "b"), toQuestionAnswer("multiple_choice_multiple", listOf("a", "b")).multipleChoiceMultiple)
        assertEquals(listOf("a"), toQuestionAnswer("multiple_choice_multiple", "a").multipleChoiceMultiple)
    }

    @Test
    fun toQuestionAnswer_booleanTypes_looselyCoerce() {
        assertEquals(true, toQuestionAnswer("yes_no", true).yesNo)
        assertEquals(true, toQuestionAnswer("yes_no", "true").yesNo)
        assertEquals(true, toQuestionAnswer("yes_no", 1).yesNo)
        assertEquals(false, toQuestionAnswer("yes_no", "false").yesNo)
        assertEquals(true, toQuestionAnswer("consent", 1).consent)
    }

    @Test
    fun toQuestionAnswer_matrixTypes_convertMaps() {
        val single = toQuestionAnswer("matrix_single_choice", mapOf("row1" to "colA"))
        assertEquals("colA", single.matrixSingleChoice?.get("row1"))

        val multi = toQuestionAnswer("matrix_multiple_choice", mapOf("row1" to listOf("colA", "colB")))
        assertEquals(listOf("colA", "colB"), multi.matrixMultipleChoice?.get("row1"))
    }

    @Test
    fun toQuestionAnswer_structuredTypes_passThrough() {
        val signature = SignatureAnswer(mode = "type", typedName = "Alice")
        assertEquals(signature, toQuestionAnswer("signature", signature).signature)

        val phone = PhoneNumberAnswer(countryCode = "+1", number = "5551234")
        assertEquals(phone, toQuestionAnswer("phone_number", phone).phoneNumber)
    }

    @Test
    fun toQuestionAnswer_displayOnlyTypes_returnEmptyAnswer() {
        assertEquals(Answer(), toQuestionAnswer("welcome", "ignored"))
        assertEquals(Answer(), toQuestionAnswer("exit_form", null))
    }

    @Test
    fun toQuestionAnswer_unknownType_fallsBackToShortAnswer() {
        assertEquals("value", toQuestionAnswer("some_future_type", "value").shortAnswer)
    }

    @Test
    fun buildSubmitRequest_mapsResponsesAndOptions() {
        val request = buildSubmitRequest(
            BuildSubmitRequestOptions(formConfigurationId = "cfg-1", feedbackIdentifier = "fb-1"),
            listOf(
                NativeFormResponse("q1", "rating", 5),
                NativeFormResponse("q2", "short_answer", "Great product!"),
            ),
        )

        assertEquals("manual", request.triggerType)
        assertEquals("cfg-1", request.formDetails.formConfigurationId)
        assertEquals("fb-1", request.formDetails.feedbackIdentifier)
        assertEquals(2, request.formDetails.response?.questions?.size)
        assertEquals(5.0, request.formDetails.response?.questions?.get(0)?.answer?.rating)
        assertEquals("Great product!", request.formDetails.response?.questions?.get(1)?.answer?.shortAnswer)
        assertTrue(request.formDetails.response?.questions?.get(0)?.type == "rating")
    }
}
