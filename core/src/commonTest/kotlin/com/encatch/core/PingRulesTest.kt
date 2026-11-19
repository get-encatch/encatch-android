package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ping and stopSession — RN 1.4.4 items 18, 19, 19b: every reply's `pingAgainIn` honoured, the
 * regular interval back only when the reply didn't schedule the next ping, no ping while a survey
 * is open or during a 429 pause, and a stop that holds through a ping in flight and a restart.
 */
class PingRulesTest {

    private lateinit var api: MockApi

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.install()
    }

    @AfterTest
    fun tearDown() = runTest {
        api.uninstall()
        Encatch.pingIntervalMs = SessionManager.PING_INTERVAL_MS
    }

    private suspend fun start(intervalMs: Long) {
        Encatch.pingIntervalMs = intervalMs
        Encatch.init("test-key")
    }

    private fun pings() = api.called("ping").size

    @Test
    fun ping_again_in_is_honoured_from_every_reply_including_the_scheduled_ping_s_own() = runTest {
        start(intervalMs = 5_000)
        api.reply = { endpoint, _ -> if (endpoint == "ping") MockApi.Reply(body = """{"pingAgainIn":0.3}""") else MockApi.Reply() }
        Encatch.startSession()
        settle(1_650)
        // 0, 0.3, 0.6, 0.9, 1.2, 1.5 s — with the old alternation the 5 s interval would take over after one
        assertTrue(pings() >= 4, "pings: ${pings()}")
    }

    @Test
    fun a_track_event_reply_schedules_the_next_ping_and_the_interval_resumes_after_it() = runTest {
        start(intervalMs = 6_000)
        api.reply = { endpoint, _ -> if (endpoint == "track-event") MockApi.Reply(body = """{"pingAgainIn":0.2}""") else MockApi.Reply() }
        Encatch.startSession(StartSessionOptions(skipImmediatePing = true))
        Encatch.trackEvent("schedules")
        eventually(timeoutMs = 3_000) { pings() == 1 }
        // the ping's reply scheduled nothing: back to the 6 s interval
        settle(1_500)
        assertEquals(1, pings())
    }

    @Test
    fun the_regular_interval_resumes_after_a_scheduled_ping_whose_reply_schedules_nothing() = runTest {
        start(intervalMs = 400)
        var first = true
        api.reply = { endpoint, _ ->
            if (endpoint == "ping" && first) { first = false; MockApi.Reply(body = """{"pingAgainIn":0.2}""") } else MockApi.Reply()
        }
        Encatch.startSession()
        settle(1_500)
        // 0, 0.2, then every 0.4 s: 0.6, 1.0, 1.4
        assertTrue(pings() >= 4, "pings: ${pings()}")
    }

    @Test
    fun no_ping_while_a_survey_is_open() = runTest {
        start(intervalMs = 200)
        Encatch.setFormVisible(true)
        Encatch.startSession(StartSessionOptions(skipImmediatePing = true))
        settle(700)
        assertEquals(0, pings())
        Encatch.setFormVisible(false)
        eventually(timeoutMs = 1_000) { pings() >= 1 }
    }

    @Test
    fun no_ping_during_a_429_pause() = runTest {
        start(intervalMs = 200)
        var first = true
        api.reply = { endpoint, _ ->
            if (endpoint == "ping" && first) {
                first = false
                MockApi.Reply(status = HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "2"))
            } else MockApi.Reply()
        }
        Encatch.startSession()
        eventually { pings() == 1 }
        settle(1_000)
        assertEquals(1, pings(), "skipped while paused")
        eventually(timeoutMs = 4_000) { pings() >= 2 }
    }

    @Test
    fun stopping_while_a_scheduled_ping_is_in_flight_does_not_restart_pings_and_its_survey_still_shows() = runTest {
        start(intervalMs = 300)
        api.reply = { endpoint, _ ->
            when (endpoint) {
                "identify-user" -> MockApi.Reply(body = """{"userId":"user-1","pingAgainIn":0.2}""")
                "ping" -> MockApi.Reply(body = """{"pingAgainIn":0.2,"nextFeedbackId":"form-p"}""", delayMs = 800)
                "show-form" -> MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.identifyUser("alice")
        eventually { pings() == 1 } // the server-scheduled ping, in flight
        Encatch.stopSession()
        settle(2_000)
        assertEquals(1, pings(), "the reply's pingAgainIn doesn't restart pings")
        assertEquals(1, api.called("show-form").size, "a survey offered in a reply still shows while stopped")
    }

    @Test
    fun a_stop_is_saved_and_respected_after_a_restart_until_start_session() = runTest {
        start(intervalMs = 200)
        Encatch.startSession(StartSessionOptions(skipImmediatePing = true))
        Encatch.stopSession()
        assertTrue(api.settings.hasKey("@encatch/session_stopped"))
        // a restart
        Encatch.init("test-key")
        api.reply = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(body = """{"pingAgainIn":0.2,"nextFeedbackId":"form-s"}""")
                "show-form" -> MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("still_works")
        eventually { api.called("track-event").size == 1 }
        eventually { api.called("show-form").size == 1 }
        settle(800)
        assertEquals(0, pings(), "no background ping while stopped")
        Encatch.setFormVisible(false) // the survey closes (no ping while one is open)
        Encatch.startSession(StartSessionOptions(skipImmediatePing = true))
        eventually(timeoutMs = 1_500) { pings() >= 1 }
        assertFalse(api.settings.hasKey("@encatch/session_stopped"))
    }

    @Test
    fun resume_session_does_not_restart_pings_while_stopped() = runTest {
        start(intervalMs = 200)
        Encatch.startSession(StartSessionOptions(skipImmediatePing = true))
        Encatch.stopSession()
        Encatch.pauseSession()
        Encatch.resumeSession()
        settle(900)
        assertEquals(0, pings())
    }

    @Test
    fun a_successful_identify_or_reset_user_clears_a_saved_stop() = runTest {
        start(intervalMs = 200)
        Encatch.stopSession()
        assertTrue(api.settings.hasKey("@encatch/session_stopped"))
        Encatch.identifyUser("alice")
        eventually { Encatch.userId != null }
        eventually { !api.settings.hasKey("@encatch/session_stopped") }
        eventually(timeoutMs = 1_500) { pings() >= 1 }

        Encatch.stopSession()
        assertTrue(api.settings.hasKey("@encatch/session_stopped"))
        Encatch.resetUser()
        assertFalse(api.settings.hasKey("@encatch/session_stopped"))
    }
}
