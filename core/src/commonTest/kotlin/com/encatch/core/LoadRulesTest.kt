package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Load — RN 1.4.4 items 2 and 4: a 429 pauses its bucket for Retry-After and is never retried
 * (submit-form excepted), an identify made during a pause is held, identical events and screens
 * within 1 s go out once.
 */
class LoadRulesTest {

    private lateinit var api: MockApi
    private val limited = MockApi.Reply(status = HttpStatusCode.TooManyRequests, body = """{"message":"slow down"}""", headers = mapOf("Retry-After" to "1"))

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.install()
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest { api.uninstall() }

    private fun eventNames() = api.called("track-event").map { it.str("eventName") }

    @Test
    fun a_429_pauses_tracking_for_retry_after_and_is_not_retried() = runTest {
        api.reply = { endpoint, body ->
            if (endpoint == "track-event" && body["eventName"]?.jsonPrimitive?.content == "a") limited else MockApi.Reply()
        }
        Encatch.trackEvent("a")
        eventually { api.called("track-event").size == 1 }
        settle(150)
        Encatch.trackEvent("b")
        Encatch.trackScreen("Paused")
        Encatch.trackFormEvent("form:show", "f-1")
        settle(400)
        assertEquals(listOf("a"), eventNames(), "skipped during the pause, the 429 not retried")
        assertTrue(api.called("track-screen").isEmpty())
        settle(800)
        Encatch.trackEvent("c")
        eventually { api.called("track-event").size == 2 }
        settle(300)
        assertEquals(listOf("a", "c"), eventNames())
        // the screen tracked during the pause is still the current one
        val device = api.called("track-event")[1].body["\$deviceInfo"]!!.jsonObject
        assertEquals("Paused", device["\$urlOrScreenName"]?.jsonPrimitive?.content)
    }

    @Test
    fun calls_already_queued_are_held_and_sent_once_when_the_pause_ends() = runTest {
        api.reply = { endpoint, body ->
            if (endpoint == "track-event" && body["eventName"]?.jsonPrimitive?.content == "x") limited.copy(delayMs = 300) else MockApi.Reply()
        }
        Encatch.trackEvent("x")
        Encatch.trackEvent("y") // queued while x is in flight
        eventually { api.called("track-event").size == 1 }
        settle(700)
        assertEquals(listOf("x"), eventNames(), "y is held")
        eventually(timeoutMs = 3_000) { api.called("track-event").size == 2 }
        settle(500)
        assertEquals(listOf("x", "y"), eventNames())
    }

    @Test
    fun an_identify_made_during_a_pause_is_held_the_latest_only() = runTest {
        api.reply = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> limited
                "identify-user" -> MockApi.Reply(body = """{"userId":"user-b"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("trigger")
        eventually { api.called("track-event").size == 1 }
        settle(100)
        Encatch.identifyUser("a")
        Encatch.identifyUser("b")
        settle(400)
        assertTrue(api.called("identify-user").isEmpty(), "held during the pause")
        eventually(timeoutMs = 3_000) { api.called("identify-user").isNotEmpty() }
        settle(400)
        assertEquals(listOf("b"), api.called("identify-user").map { it.str("userName") })
        assertEquals("user-b", Encatch.userId)
    }

    @Test
    fun a_submit_waits_out_the_pause_and_retries_up_to_three_times() = runTest {
        api.reply = { endpoint, _ -> if (endpoint == "submit-form") limited else MockApi.Reply() }
        // real time: the submit's wait for the pause is a delay on the caller's dispatcher
        withContext(Dispatchers.Default) {
            Encatch.submitForm(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "form-1")))
        }
        val submits = api.called("submit-form")
        assertEquals(3, submits.size)
        assertTrue(submits[1].atMs - submits[0].atMs >= 900, "waited for the pause")
        assertTrue(submits[2].atMs - submits[1].atMs >= 900, "waited for the pause")
    }

    @Test
    fun a_submit_goes_through_after_one_429() = runTest {
        var first = true
        api.reply = { endpoint, _ ->
            if (endpoint == "submit-form" && first) { first = false; limited } else MockApi.Reply()
        }
        withContext(Dispatchers.Default) {
            Encatch.submitForm(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "form-1")))
        }
        assertEquals(2, api.called("submit-form").size)
    }

    @Test
    fun a_show_form_429_is_not_retried_and_pauses_show_form_only() = runTest {
        api.reply = { endpoint, _ -> if (endpoint == "show-form") limited else MockApi.Reply() }
        Encatch.showForm("form-f")
        eventually { api.called("show-form").size == 1 }
        settle(150)
        Encatch.showForm("form-g")
        Encatch.trackEvent("still_sent")
        eventually { api.called("track-event").size == 1 }
        settle(300)
        assertEquals(1, api.called("show-form").size)
        settle(900)
        api.reply = { endpoint, _ -> if (endpoint == "show-form") MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid"}""") else MockApi.Reply() }
        Encatch.showForm("form-g")
        eventually { api.called("show-form").size == 2 }
    }

    @Test
    fun identical_events_within_one_second_are_sent_once() = runTest {
        repeat(5) { Encatch.trackEvent("tap") }
        Encatch.trackEvent("other")
        eventually { api.called("track-event").size == 2 }
        settle(1_100)
        Encatch.trackEvent("tap")
        eventually { api.called("track-event").size == 3 }
        settle(200)
        assertEquals(listOf("tap", "other", "tap"), eventNames())
    }

    @Test
    fun the_same_screen_again_within_one_second_is_sent_once_a_to_b_to_a_in_full() = runTest {
        repeat(4) { Encatch.trackScreen("Home") }
        eventually { api.called("track-screen").size == 1 }
        Encatch.trackScreen("Cart")
        Encatch.trackScreen("Home")
        eventually { api.called("track-screen").size == 3 }
        settle(300)
        val screens = api.called("track-screen").map { it.body["\$deviceInfo"]!!.jsonObject["\$urlOrScreenName"]?.jsonPrimitive?.content }
        assertEquals(listOf("Home", "Cart", "Home"), screens)
    }
}
