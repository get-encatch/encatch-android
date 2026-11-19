package com.encatch.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A survey the server offers in a reply (`nextFeedbackId` + `onPageDelay`) is shown, from every
 * reply, one at a time — RN 1.4.4 items 6 and 7, e2e issue AN-001.
 */
class SurveyInReplyTest {

    private data class Call(val endpoint: String, val body: JsonObject)

    // written from the SDK's dispatcher, read from the test's: a state flow, updated atomically
    private val calls = MutableStateFlow<List<Call>>(emptyList())
    @Volatile private var replies: (String, JsonObject) -> Pair<HttpStatusCode, String> = { _, _ -> HttpStatusCode.OK to "{}" }

    private fun showForms(): List<Call> = synchronizedCalls().filter { it.endpoint == "show-form" }
    private fun synchronizedCalls(): List<Call> = calls.value

    @BeforeTest
    fun setUp() = runTest {
        calls.value = emptyList()
        Encatch.settingsFactory = { InMemorySettings() }
        Encatch.engineFactory = {
            MockEngine { request ->
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: "{}"
                val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                calls.update { it + Call(endpoint, body) }
                val (status, reply) = replies(endpoint, body)
                respond(reply, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest {
        Encatch.clearAll()
        Encatch.engineFactory = null
        Encatch.settingsFactory = null
    }

    /** Waits, in real time (the SDK's own scope runs on Dispatchers.Default), until [done] holds. */
    private suspend fun eventually(timeoutMs: Long = 5_000, done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) { while (!done()) delay(20) }
    }

    private suspend fun settle(ms: Long = 400) = withContext(Dispatchers.Default) { delay(ms) }

    private val formReply = """{"feedbackConfigurationId":"0000-form-uuid","questionnaireFields":{}}"""

    @Test
    fun a_survey_offered_in_a_track_event_reply_is_shown_once() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-a","pingOnNextPageVisit":true}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("rp_c")
        eventually { showForms().isNotEmpty() }
        settle()
        val shows = showForms()
        assertEquals(1, shows.size)
        assertEquals("form-a", shows[0].body["formSlugOrId"]?.jsonPrimitive?.content)
        assertEquals("automatic", shows[0].body["triggerType"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_second_offer_while_the_first_is_open_or_loading_is_skipped() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event", "track-screen" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-a"}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("one")
        Encatch.trackScreen("Home")
        Encatch.trackEvent("two")
        eventually { showForms().isNotEmpty() }
        settle(600)
        assertEquals(1, showForms().size, "one survey at a time")
        // closed: the next offer shows again
        Encatch.setFormVisible(false)
        Encatch.trackEvent("three")
        eventually { showForms().size == 2 }
    }

    @Test
    fun on_page_delay_is_waited_out() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-d","onPageDelay":1}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("later")
        settle(500)
        assertTrue(showForms().isEmpty(), "not before onPageDelay")
        eventually { showForms().size == 1 }
    }

    @Test
    fun an_older_server_s_form_configuration_id_is_shown_at_once_when_alone() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"formConfigurationId":"form-legacy","onPageDelay":5}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("legacy")
        eventually(timeoutMs = 2_000) { showForms().isNotEmpty() }
        assertEquals("form-legacy", showForms()[0].body["formSlugOrId"]?.jsonPrimitive?.content)
    }

    @Test
    fun next_feedback_id_wins_over_the_legacy_field() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"formConfigurationId":"form-legacy","nextFeedbackId":"form-next"}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("both")
        eventually { showForms().isNotEmpty() }
        assertEquals("form-next", showForms()[0].body["formSlugOrId"]?.jsonPrimitive?.content)
    }

    @Test
    fun a_second_offer_while_one_waits_out_its_delay_is_skipped_and_stop_session_cancels_it() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-d","onPageDelay":1}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("a")
        settle(300)
        Encatch.trackEvent("b")
        eventually { showForms().size == 1 }
        settle(1_300)
        assertEquals(1, showForms().size, "one pending show at a time")
        Encatch.setFormVisible(false)
        Encatch.trackEvent("c")
        settle(300)
        Encatch.stopSession()
        settle(1_200)
        assertEquals(1, showForms().size, "stopSession cancels the waiting show")
    }

    @Test
    fun a_close_of_a_replaced_survey_does_not_free_the_guard() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-auto"}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.showForm("form-b")
        eventually { showForms().size == 1 }
        // the survey it replaced reports its close late
        Encatch.setFormVisible(false, "form-a")
        Encatch.trackEvent("offer")
        settle(500)
        assertEquals(1, showForms().size, "form-b is still open")
        // form-b closing frees it, by either of its names
        Encatch.setFormVisible(false, "0000-form-uuid")
        Encatch.trackEvent("offer-again")
        eventually { showForms().size == 2 }
    }

    @Test
    fun asking_again_for_the_open_survey_keeps_it_another_replaces_it() = runTest {
        replies = { endpoint, _ -> if (endpoint == "show-form") HttpStatusCode.OK to formReply else HttpStatusCode.OK to "{}" }
        Encatch.showForm("form-m")
        eventually { showForms().size == 1 }
        Encatch.showForm("form-m")
        settle()
        assertEquals(1, showForms().size, "the same form, open: ignored")
        Encatch.showForm("form-n")
        eventually { showForms().size == 2 }
        assertEquals("manual", showForms()[1].body["triggerType"]?.jsonPrimitive?.content)
    }

    @Test
    fun not_eligible_now_releases_the_guard() = runTest {
        val eligible = MutableStateFlow(false)
        replies = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-e"}"""
                "show-form" -> if (eligible.value) HttpStatusCode.OK to formReply else HttpStatusCode.NotFound to """{"message":"User is not eligible for this form"}"""
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.trackEvent("first")
        eventually { showForms().size == 1 }
        settle()
        eligible.value = true
        Encatch.trackEvent("second")
        eventually { showForms().size == 2 }
    }

    @Test
    fun a_ping_reply_is_read() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "ping" -> HttpStatusCode.OK to """{"nextFeedbackId":"form-p"}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.startSession()
        eventually { showForms().isNotEmpty() }
        assertEquals("form-p", showForms()[0].body["formSlugOrId"]?.jsonPrimitive?.content)
    }

    @Test
    fun an_identify_reply_is_read() = runTest {
        replies = { endpoint, _ ->
            when (endpoint) {
                "identify-user" -> HttpStatusCode.OK to """{"userId":"user-1","nextFeedbackId":"form-i"}"""
                "show-form" -> HttpStatusCode.OK to formReply
                else -> HttpStatusCode.OK to "{}"
            }
        }
        Encatch.identifyUser("someone")
        eventually { showForms().isNotEmpty() }
        assertEquals("form-i", showForms()[0].body["formSlugOrId"]?.jsonPrimitive?.content)
    }
}
