package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * setUserProperties(userName, traits), as the React Native SDK 1.5.0 (docs/HANDOVER-IDENTIFY-DEDUPE-
 * AND-SET-USER-PROPERTIES.md, part 2): updates the confirmed current user's properties through
 * identify-user; never deduped; waits for a pending identify of that user and for an open survey.
 */
class SetUserPropertiesTest {

    private lateinit var api: MockApi

    /** identify-user replies: the user's id (`uid-<name>`), unless [props] says otherwise for an update. */
    private fun identifyReply(body: JsonObject, delayMs: Long = 0) =
        MockApi.Reply(body = """{"userId":"uid-${body["userName"]?.jsonPrimitive?.content}"}""", delayMs = delayMs)

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.reply = { endpoint, body -> if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply() }
        api.install()
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest { api.uninstall() }

    private val identifies get() = api.called("identify-user")
    /** The identify-user requests that are property updates (they carry the user's id and traits). */
    private val updates get() = identifies.filter { isUpdate(it.body) }
    private fun isUpdate(body: JsonObject) = body["userAttributes"]?.jsonObject?.get("\$set")?.jsonObject?.containsKey("props") == true ||
        body["userAttributes"]?.jsonObject?.containsKey("\$increment") == true
    private fun props(v: String) = UserTraits(set = mapOf("props" to JsonPrimitive(v)))
    private fun MockApi.Call.propsValue() = body["userAttributes"]?.jsonObject?.get("\$set")?.jsonObject?.get("props")?.jsonPrimitive?.content

    private suspend fun identify(name: String, traits: UserTraits? = null, options: IdentifyOptions? = null) {
        Encatch.identifyUser(name, traits, options)
        settle(150)
    }

    private val sig = IdentifyOptions(secure = SecureOptions("sig-1", "1763500000000"))

    @Test
    fun a_confirmed_user_sends_name_id_traits_and_the_stored_signature() = runTest {
        identify("alice", options = sig)
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 1 }
        val u = updates.single()
        assertEquals("alice", u.str("userName"))
        assertEquals("uid-alice", u.str("userId"))
        assertEquals("a", u.propsValue())
        assertEquals("alice", u.header("X-User-Name"))
        assertEquals("sig-1", u.header("X-User-Signature"))
        assertEquals("1763500000000", u.header("X-User-Session-Time"))
        assertNull(u.body["userSignature"], "the signature goes in the headers, not the body")
        // the stored signature is kept and still sent afterwards
        Encatch.trackEvent("after")
        eventually { api.called("track-event").isNotEmpty() }
        assertEquals("sig-1", api.called("track-event").last().header("X-User-Signature"))
    }

    @Test
    fun never_deduped() = runTest {
        identify("alice")
        Encatch.setUserProperties("alice", props("a"))
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 2 }
        settle(200)
        assertEquals(2, updates.size)
    }

    @Test
    fun a_visitor_or_another_user_is_ignored() = runTest {
        Encatch.setUserProperties("alice", props("a"))
        identify("alice")
        Encatch.setUserProperties("bob", props("b"))
        settle(300)
        assertEquals(0, updates.size)
    }

    @Test
    fun during_a_switch_the_old_user_is_wrong_and_the_new_one_waits_for_its_identify() = runTest {
        identify("alice")
        api.reply = { endpoint, body -> if (endpoint == "identify-user") identifyReply(body, delayMs = if (isUpdate(body)) 0 else 400) else MockApi.Reply() }
        Encatch.identifyUser("bob")
        Encatch.setUserProperties("alice", props("for-alice"))
        Encatch.setUserProperties("bob", props("for-bob"))
        eventually { updates.isNotEmpty() }
        settle(300)
        assertEquals(listOf("for-bob"), updates.map { it.propsValue() })
        val bobIdentify = identifies.indexOfFirst { it.str("userName") == "bob" && !isUpdate(it.body) }
        assertTrue(bobIdentify < identifies.indexOf(updates.single()), "sent after bob's identify")
        assertEquals("uid-bob", updates.single().str("userId"))
    }

    @Test
    fun a_failed_switch_leaves_the_new_user_unidentified() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user" && body["userName"]?.jsonPrimitive?.content == "bob") MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""")
            else if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply()
        }
        Encatch.identifyUser("bob")
        Encatch.setUserProperties("bob", props("b"))
        Encatch.setUserProperties("alice", props("a"))
        settle(400)
        assertEquals(0, updates.size)
    }

    @Test
    fun a_switch_back_whose_identify_fails_is_not_confirmed() = runTest {
        identify("alice")
        var aliceIdentifies = 0
        api.reply = { endpoint, body ->
            when {
                endpoint != "identify-user" -> MockApi.Reply()
                isUpdate(body) -> identifyReply(body)
                body["userName"]?.jsonPrimitive?.content == "bob" -> identifyReply(body, delayMs = 300)
                aliceIdentifies++ == 0 -> MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""")
                else -> identifyReply(body)
            }
        }
        Encatch.identifyUser("bob")
        Encatch.identifyUser("alice") // back to alice: this identify fails
        Encatch.setUserProperties("alice", props("a"))
        settle(800)
        assertEquals(0, updates.size, "alice's earlier confirmation doesn't count after a switch")
    }

    @Test
    fun a_new_users_update_waits_for_their_first_identify() = runTest {
        api.reply = { endpoint, body -> if (endpoint == "identify-user") identifyReply(body, delayMs = if (isUpdate(body)) 0 else 300) else MockApi.Reply() }
        Encatch.identifyUser("bob")
        Encatch.setUserProperties("bob", props("b"))
        eventually { updates.size == 1 }
        assertEquals("uid-bob", updates.single().str("userId"))
    }

    @Test
    fun reset_while_it_is_queued_behind_a_slow_request_stops_it() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(delayMs = 500)
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("slow")
        eventually { api.called("track-event").isNotEmpty() }
        Encatch.setUserProperties("alice", props("a")) // passes the first check, then waits in the queue
        Encatch.resetUser()
        settle(800)
        assertEquals(0, updates.size)
    }

    @Test
    fun reset_is_not_held_up_while_an_update_waits_for_a_held_identify() = runTest {
        identify("alice")
        pauseTracking(retryAfterSec = 2)
        Encatch.identifyUser("bob") // held by the pause
        backgroundScope.launch { Encatch.setUserProperties("bob", props("b")) }
        settle(100)
        val start = TimeSource.Monotonic.markNow()
        Encatch.resetUser()
        assertTrue(start.elapsedNow().inWholeMilliseconds < 500, "resetUser waited ${start.elapsedNow()}")
        settle(2_500)
        assertEquals(0, updates.size, "bob was reset: his update isn't sent")
    }

    @Test
    fun an_update_waiting_for_a_held_identify_still_lets_only_the_latest_identify_go() = runTest {
        identify("alice")
        pauseTracking(retryAfterSec = 1)
        Encatch.identifyUser("bob", UserTraits(set = mapOf("t" to JsonPrimitive(1))))
        backgroundScope.launch { Encatch.setUserProperties("bob", props("b")) }
        settle(100)
        Encatch.identifyUser("bob", UserTraits(set = mapOf("t" to JsonPrimitive(2))))
        eventually(4_000) { updates.size == 1 }
        val bobs = identifies.filter { it.str("userName") == "bob" && !isUpdate(it.body) }
        assertEquals(1, bobs.size, "only the latest held identify")
        assertTrue(identifies.indexOf(bobs.single()) < identifies.indexOf(updates.single()), "the update after it")
    }

    @Test
    fun it_doesnt_restart_a_stopped_session_or_change_the_identity() = runTest {
        identify("alice")
        Encatch.stopSession()
        var identified = 0
        val off = EncatchInternalEmitter.on { if (it is InternalEvent.UserIdentified) identified++ }
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 1 }
        settle(200)
        off()
        assertEquals("true", api.settings.getStringOrNull("@encatch/session_stopped"))
        assertEquals(0, identified, "no \"user identified\"")
        assertTrue(Encatch.isIdentified())
    }

    @Test
    fun it_clears_identifys_traits_memory_but_not_the_user() = runTest {
        val t = UserTraits(set = mapOf("plan" to JsonPrimitive("pro")))
        identify("alice", t)
        Encatch.setUserProperties("alice", UserTraits(set = mapOf("plan" to JsonPrimitive("team"))))
        settle(200)
        identify("alice", t)
        assertEquals(3, identifies.size, "identify, the update, the same identify again")
        identify("alice")
        assertEquals(3, identifies.size, "a no-traits identify is still a repeat")
    }

    private suspend fun pauseTracking(retryAfterSec: Int = 1) {
        val previous = api.reply
        api.reply = { endpoint, body ->
            if (endpoint == "track-event") MockApi.Reply(status = HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "$retryAfterSec"))
            else previous(endpoint, body)
        }
        Encatch.trackEvent("pause-me-${currentTimeMillis()}")
        eventually { api.called("track-event").isNotEmpty() }
        settle(100)
    }

    @Test
    fun during_a_429_pause_it_is_ignored() = runTest {
        identify("alice")
        pauseTracking()
        Encatch.setUserProperties("alice", props("a"))
        settle(1_500)
        assertEquals(0, updates.size)
    }

    @Test
    fun one_waiting_for_a_held_identify_of_its_user_is_sent_after_it() = runTest {
        identify("alice")
        pauseTracking()
        Encatch.identifyUser("bob")
        backgroundScope.launch { Encatch.setUserProperties("bob", props("b")) }
        eventually(4_000) { updates.size == 1 }
        val bob = identifies.indexOfFirst { it.str("userName") == "bob" && !isUpdate(it.body) }
        assertTrue(bob in 0 until identifies.indexOf(updates.single()))
    }

    @Test
    fun with_an_expired_signature_it_is_ignored() = runTest {
        identify("alice", options = sig)
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"message":"expired"}""")
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("expires")
        settle(300)
        Encatch.setUserProperties("alice", props("a"))
        settle(300)
        assertEquals(0, updates.size)
    }

    @Test
    fun a_401_on_an_update_is_a_signature_expiry() = runTest {
        identify("alice", options = sig)
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user" && isUpdate(body)) MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"message":"expired"}""")
            else if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply()
        }
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 1 }
        settle(200)
        Encatch.trackEvent("skipped-now")
        settle(300)
        assertTrue(api.called("track-event").isEmpty(), "events are skipped while the signature is expired")
    }

    @Test
    fun a_401_for_the_previous_user_after_a_reset_is_not_an_expiry() = runTest {
        identify("alice", options = sig)
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user" && isUpdate(body)) MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"message":"expired"}""", delayMs = 300)
            else if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply()
        }
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 1 }
        Encatch.resetUser()
        settle(500)
        Encatch.trackEvent("visitor-event")
        eventually { api.called("track-event").isNotEmpty() }
    }

    @Test
    fun increments_are_never_retried_and_sets_are() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user" && isUpdate(body)) MockApi.Reply(status = HttpStatusCode.InternalServerError, body = """{"message":"oops"}""")
            else if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply()
        }
        Encatch.setUserProperties("alice", UserTraits(increment = mapOf("logins" to 1.0)))
        settle(2_600)
        assertEquals(1, updates.size, "an increment goes once")
        Encatch.setUserProperties("alice", props("a"))
        eventually(4_000) { updates.count { it.propsValue() == "a" } == 2 }
    }

    @Test
    fun updates_go_in_call_order_even_when_the_first_is_retried() = runTest {
        identify("alice")
        var failedOnce = false
        api.reply = { endpoint, body ->
            when {
                endpoint != "identify-user" -> MockApi.Reply()
                isUpdate(body) && !failedOnce -> { failedOnce = true; MockApi.Reply(status = HttpStatusCode.InternalServerError, body = """{"message":"oops"}""") }
                else -> identifyReply(body)
            }
        }
        Encatch.setUserProperties("alice", props("old"))
        Encatch.setUserProperties("alice", props("new"))
        eventually(5_000) { updates.size == 3 }
        assertEquals(listOf("old", "old", "new"), updates.map { it.propsValue() })
    }

    @Test
    fun it_waits_while_a_survey_is_open_and_shows_a_survey_in_its_reply() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when {
                endpoint == "identify-user" && isUpdate(body) -> MockApi.Reply(body = """{"nextFeedbackId":"form-x"}""")
                endpoint == "identify-user" -> identifyReply(body)
                endpoint == "show-form" -> MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid","questionnaireFields":{}}""")
                else -> MockApi.Reply()
            }
        }
        // a long poll: only the close itself can wake the update in time
        Encatch.surveyIdlePollMs = 10_000
        try {
            Encatch.setFormVisible(true)
            backgroundScope.launch { Encatch.setUserProperties("alice", props("a")) }
            settle(800)
            assertEquals(0, updates.size, "not while the survey is open")
            val closed = TimeSource.Monotonic.markNow()
            Encatch.setFormVisible(false)
            eventually { updates.size == 1 }
            assertTrue(closed.elapsedNow().inWholeMilliseconds < 1_000, "woken by the close, not the poll")
            eventually { api.called("show-form").isNotEmpty() }
        } finally {
            Encatch.surveyIdlePollMs = 500
        }
    }

    @Test
    fun it_waits_while_an_automatic_show_is_pending() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(body = """{"nextFeedbackId":"form-d","onPageDelay":1}""")
                "show-form" -> MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid","questionnaireFields":{}}""")
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("offer")
        eventually { api.called("track-event").isNotEmpty() }
        settle(100)
        backgroundScope.launch { Encatch.setUserProperties("alice", props("a")) }
        eventually(3_000) { api.called("show-form").isNotEmpty() }
        settle(300)
        assertEquals(0, updates.size, "not before the survey shown after the delay is closed")
        Encatch.setFormVisible(false)
        eventually { updates.size == 1 }
    }

    @Test
    fun a_reply_after_a_reset_is_ignored() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when {
                endpoint == "identify-user" && isUpdate(body) -> MockApi.Reply(body = """{"nextFeedbackId":"form-x","${'$'}feedbackTransactions":"tx-1"}""", delayMs = 300)
                endpoint == "identify-user" -> identifyReply(body)
                else -> MockApi.Reply(body = """{"feedbackConfigurationId":"0000-form-uuid","questionnaireFields":{}}""")
            }
        }
        Encatch.setUserProperties("alice", props("a"))
        eventually { updates.size == 1 }
        Encatch.resetUser()
        settle(600)
        assertTrue(api.called("show-form").isEmpty(), "no survey for the visitor")
        Encatch.trackEvent("visitor")
        eventually { api.called("track-event").isNotEmpty() }
        assertNull(api.called("track-event").last().body["\$feedbackTransactions"], "no transactions for the visitor")
    }

    @Test
    fun clear_all_while_one_waits_for_a_survey_sends_nothing_afterwards() = runTest {
        identify("alice")
        Encatch.setFormVisible(true)
        backgroundScope.launch { Encatch.setUserProperties("alice", props("a")) }
        settle(200)
        Encatch.clearAll()
        Encatch.init("test-key")
        identify("alice")
        settle(800)
        assertEquals(0, updates.size)
    }

    @Test
    fun the_traits_are_copied_at_the_call() = runTest {
        identify("alice")
        Encatch.setFormVisible(true)
        val set = mutableMapOf<String, kotlinx.serialization.json.JsonElement>("props" to JsonPrimitive("at-call"))
        backgroundScope.launch { Encatch.setUserProperties("alice", UserTraits(set = set)) }
        settle(200)
        set["props"] = JsonPrimitive("changed")
        Encatch.setFormVisible(false)
        eventually { updates.size == 1 }
        assertEquals("at-call", updates.single().propsValue())
    }

    @Test
    fun empty_or_unsendable_traits_send_nothing() = runTest {
        identify("alice")
        Encatch.setUserProperties("alice", UserTraits())
        Encatch.setUserProperties("alice", UserTraits(set = emptyMap(), unset = emptyList()))
        Encatch.setUserProperties("alice", UserTraits(set = mapOf("props" to JsonPrimitive(Double.NaN))))
        Encatch.setUserProperties("alice", UserTraits(increment = mapOf("n" to Double.POSITIVE_INFINITY)))
        settle(300)
        assertEquals(1, identifies.size)
    }

    @Test
    fun stop_releases_an_update_waiting_for_a_survey() = runTest {
        identify("alice")
        Encatch.setFormVisible(true)
        var returned = false
        backgroundScope.launch { Encatch.setUserProperties("alice", props("a")); returned = true }
        settle(200)
        Encatch.stop()
        eventually { returned }
        Encatch.setFormVisible(false)
        settle(600)
        assertEquals(0, updates.size)
    }

    @Test
    fun a_survey_opened_while_it_was_queued_holds_it_until_closed() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(delayMs = 400)
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("slow")
        eventually { api.called("track-event").isNotEmpty() }
        Encatch.setUserProperties("alice", props("a")) // queued behind the slow event
        Encatch.setFormVisible(true) // a survey opens before it is sent
        settle(800)
        assertEquals(0, updates.size)
        Encatch.setFormVisible(false)
        eventually { updates.size == 1 }
    }

    @Test
    fun clear_all_with_a_held_identify_doesnt_leave_a_later_update_waiting_forever() = runTest {
        identify("alice")
        pauseTracking(retryAfterSec = 2)
        Encatch.identifyUser("bob") // held by the pause
        assertEquals(1, Encatch.retryQueueSizeForTest(), "bob's identify is held")
        Encatch.clearAll()
        assertEquals(0, Encatch.retryQueueSizeForTest(), "clearAll emptied the queue")
        api.reply = { endpoint, body -> if (endpoint == "identify-user") identifyReply(body) else MockApi.Reply() }
        Encatch.init("test-key")
        identify("bob")
        val returned = withContext(Dispatchers.Default) { withTimeoutOrNull(3_000) { Encatch.setUserProperties("bob", props("b")) } }
        assertTrue(returned != null, "setUserProperties returned")
        eventually { updates.size == 1 }
        settle(2_200)
        assertEquals(1, identifies.count { it.str("userName") == "bob" && !isUpdate(it.body) }, "the cleared identify never went out")
    }

    @Test
    fun two_calls_waiting_at_once_go_out_in_call_order() = runTest {
        identify("alice")
        repeat(8) { round ->
            Encatch.setFormVisible(true)
            withContext(Dispatchers.Default) {
                launch { Encatch.setUserProperties("alice", props("first-$round")) }
                delay(30)
                launch { Encatch.setUserProperties("alice", props("second-$round")) }
                delay(100)
                Encatch.setFormVisible(false)
            }
            eventually { updates.size == 2 * (round + 1) }
        }
        val values = updates.map { it.propsValue() }
        assertEquals((0 until 8).flatMap { listOf("first-$it", "second-$it") }, values)
    }

    @Test
    fun a_re_init_releases_an_update_waiting_for_an_identify() = runTest {
        identify("alice")
        pauseTracking(retryAfterSec = 2)
        Encatch.identifyUser("bob") // held
        var returned = false
        backgroundScope.launch { Encatch.setUserProperties("bob", props("b")); returned = true }
        settle(100)
        Encatch.init("test-key")
        eventually { returned }
        settle(2_500)
        assertEquals(0, updates.size)
    }

    @Test
    fun stop_releases_an_update_waiting_for_an_identify() = runTest {
        identify("alice")
        pauseTracking(retryAfterSec = 1)
        Encatch.identifyUser("bob") // held
        var returned = false
        backgroundScope.launch { Encatch.setUserProperties("bob", props("b")); returned = true }
        settle(100)
        Encatch.stop()
        eventually { returned }
        settle(1_800)
        assertEquals(0, updates.size, "not sent once bob's identify goes out after the pause")
    }

    @Test
    fun a_signature_expiring_while_it_is_queued_stops_it() = runTest {
        identify("alice", options = sig)
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"message":"expired"}""", delayMs = 400)
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("expires-soon")
        eventually { api.called("track-event").isNotEmpty() }
        Encatch.setUserProperties("alice", props("a")) // passes the first check, waits in the queue
        settle(900)
        assertEquals(0, updates.size)
    }

    @Test
    fun stop_while_it_is_queued_behind_a_slow_request_stops_it() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(delayMs = 500)
                "identify-user" -> identifyReply(body)
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("slow")
        eventually { api.called("track-event").isNotEmpty() }
        Encatch.setUserProperties("alice", props("a"))
        Encatch.stop()
        settle(900)
        assertEquals(0, updates.size)
    }
}
