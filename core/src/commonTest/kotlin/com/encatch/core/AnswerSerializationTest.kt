package com.encatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnswerSerializationTest {

    @Test
    fun answer_singleChoice_roundTrips() {
        val answer = Answer(singleChoice = "option-a")
        val json = EncatchJson.encodeToString(Answer.serializer(), answer)
        val decoded = EncatchJson.decodeFromString(Answer.serializer(), json)
        assertEquals(answer, decoded)
    }

    @Test
    fun answer_ratingMatrix_supportsNumberOrStringValues() {
        val json = """{"ratingMatrix":{"row1":5,"row2":"custom"}}"""
        val decoded = EncatchJson.decodeFromString(Answer.serializer(), json)
        assertEquals(2, decoded.ratingMatrix?.size)
    }

    @Test
    fun schedulerAnswer_googleCalendar_encodesProviderDiscriminator() {
        val answer = Answer(scheduler = SchedulerAnswer.GoogleCalendar(bookedAt = "1700000000"))
        val json = EncatchJson.encodeToString(Answer.serializer(), answer)
        assertTrue(json.contains("\"provider\":\"google_calendar\""))
        assertTrue(json.contains("\"bookedAt\":\"1700000000\""))

        val decoded = EncatchJson.decodeFromString(Answer.serializer(), json)
        assertEquals(answer, decoded)
    }

    @Test
    fun schedulerAnswer_calendly_encodesProviderDiscriminator() {
        val answer = Answer(
            scheduler = SchedulerAnswer.Calendly(
                slotStart = "2026-05-15T14:00:00Z",
                slotEnd = "2026-05-15T14:30:00Z",
                eventId = "evt-1",
                bookedAt = "1700000000",
            ),
        )
        val json = EncatchJson.encodeToString(Answer.serializer(), answer)
        assertTrue(json.contains("\"provider\":\"calendly\""))

        val decoded = EncatchJson.decodeFromString(Answer.serializer(), json)
        assertEquals(answer, decoded)
    }

    @Test
    fun formDetails_submitFormRequest_roundTrips() {
        val request = SubmitFormRequest(
            triggerType = "manual",
            formDetails = FormDetails(
                formConfigurationId = "cfg-1",
                isPartialSubmit = false,
                response = FormResponse(
                    questions = listOf(
                        QuestionResponse(questionId = "q1", answer = Answer(shortAnswer = "hello")),
                    ),
                ),
            ),
        )
        val json = EncatchJson.encodeToString(SubmitFormRequest.serializer(), request)
        val decoded = EncatchJson.decodeFromString(SubmitFormRequest.serializer(), json)
        assertEquals(request, decoded)
    }
}
