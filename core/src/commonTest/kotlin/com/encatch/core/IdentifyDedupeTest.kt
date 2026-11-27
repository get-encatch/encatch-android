package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * identifyUser() dedupe, as the React Native SDK 1.5.0 (docs/HANDOVER-IDENTIFY-DEDUPE-AND-SET-USER-
 * PROPERTIES.md, part 1): after the first identify of each app run, a repeat for the same user with
 * the same traits, or none, is not sent.
 */
class IdentifyDedupeTest {

    private lateinit var api: MockApi

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"uid-${body["userName"]?.let { (it as JsonPrimitive).content }}"}""") else MockApi.Reply()
        }
        api.install()
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest { api.uninstall() }

    private val identifies get() = api.called("identify-user")
    private fun plan(p: String) = UserTraits(set = mapOf("plan" to JsonPrimitive(p)))

    /** Identifies, then waits until the request went out (or long enough to be sure it didn't). */
    private suspend fun identify(name: String, traits: UserTraits? = null, options: IdentifyOptions? = null) {
        Encatch.identifyUser(name, traits, options)
        settle(150)
    }

    @Test
    fun a_restored_user_sends_the_first_identify_of_the_run_then_skips_the_repeat() = runTest {
        identify("alice")
        assertEquals(1, identifies.size)
        Encatch.init("test-key") // a new app run: the user is restored, no identify memory
        identify("alice")
        identify("alice")
        assertEquals(2, identifies.size, "first of this run sent, the repeat not")
    }

    @Test
    fun the_same_user_without_traits_after_traits_is_a_duplicate() = runTest {
        identify("alice", plan("pro"))
        identify("alice")
        assertEquals(1, identifies.size)
    }

    @Test
    fun key_order_and_unset_order_dont_matter_and_the_callers_list_is_kept() = runTest {
        val unset = listOf("b", "a")
        identify("alice", UserTraits(set = mapOf("x" to JsonPrimitive(1), "y" to JsonPrimitive(2)), unset = unset))
        identify("alice", UserTraits(set = linkedMapOf("y" to JsonPrimitive(2), "x" to JsonPrimitive(1)), unset = listOf("a", "b")))
        assertEquals(1, identifies.size)
        assertEquals(listOf("b", "a"), unset)
    }

    @Test
    fun empty_operations_count_as_no_traits() = runTest {
        identify("alice")
        identify("alice", UserTraits(set = emptyMap(), unset = emptyList(), increment = emptyMap()))
        assertEquals(1, identifies.size)
    }

    @Test
    fun changed_traits_and_users_are_sent() = runTest {
        identify("alice", plan("a"))
        identify("alice", plan("b"))
        identify("alice", plan("a"))
        assertEquals(3, identifies.size, "A, B, A traits: three sent")
        identify("bob")
        identify("alice")
        assertEquals(5, identifies.size, "users A, B, A: each sent")
    }

    @Test
    fun the_same_increment_twice_sends_once() = runTest {
        identify("alice", UserTraits(increment = mapOf("logins" to 1.0)))
        identify("alice", UserTraits(increment = mapOf("logins" to 1.0)))
        assertEquals(1, identifies.size)
    }

    @Test
    fun a_burst_for_a_new_user_sends_one_while_the_first_is_in_flight() = runTest {
        api.reply = { endpoint, _ -> if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"uid-bob"}""", delayMs = 500) else MockApi.Reply() }
        repeat(20) { Encatch.identifyUser("bob") }
        settle(900)
        assertEquals(1, identifies.size)
    }

    @Test
    fun a_burst_during_a_switch_sends_one() = runTest {
        identify("alice")
        api.reply = { endpoint, _ -> if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"uid-bob"}""", delayMs = 500) else MockApi.Reply() }
        repeat(20) { Encatch.identifyUser("bob", plan("pro")) }
        settle(900)
        assertEquals(2, identifies.size)
    }

    private suspend fun pauseTracking() {
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(status = HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "1"))
                "identify-user" -> MockApi.Reply(body = """{"userId":"uid-${(body["userName"] as JsonPrimitive).content}"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("pause-me")
        eventually { api.called("track-event").isNotEmpty() }
        settle(100)
    }

    @Test
    fun repeats_of_sent_traits_during_a_429_pause_send_nothing() = runTest {
        identify("alice", plan("pro"))
        pauseTracking()
        repeat(5) { Encatch.identifyUser("alice", plan("pro")) }
        settle(1_800)
        assertEquals(1, identifies.size, "all five are the identify already sent")
    }

    @Test
    fun repeats_of_new_traits_during_a_429_pause_send_one_when_it_ends() = runTest {
        identify("alice")
        pauseTracking()
        repeat(5) { Encatch.identifyUser("alice", plan("pro")) }
        settle(1_800)
        assertEquals(2, identifies.size, "the first identify, then one of the five after the pause")
    }

    @Test
    fun an_identify_replaced_while_held_forgets_its_traits_and_the_replacement_is_kept() = runTest {
        identify("alice")
        pauseTracking()
        Encatch.identifyUser("alice", plan("pro")) // held
        Encatch.identifyUser("alice", options = IdentifyOptions(locale = "fr")) // replaces it
        Encatch.identifyUser("alice", options = IdentifyOptions(locale = "fr")) // a repeat
        settle(1_800)
        assertEquals(2, identifies.size)
        assertNull(identifies.last().body["userAttributes"])
        identify("alice", plan("pro"))
        assertEquals(3, identifies.size, "the replaced identify's traits go again")
        identify("alice", options = IdentifyOptions(locale = "fr"))
        assertEquals(3, identifies.size, "the replacing identify's memory is kept")
    }

    @Test
    fun an_identify_dropped_on_a_429_can_be_sent_again() = runTest {
        var n = 0
        api.reply = { endpoint, _ ->
            when {
                endpoint == "identify-user" && n++ == 0 -> MockApi.Reply(status = HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "1"))
                endpoint == "identify-user" -> MockApi.Reply(body = """{"userId":"uid-alice"}""")
                else -> MockApi.Reply()
            }
        }
        identify("alice", plan("pro"))
        assertEquals(1, identifies.size)
        repeat(5) { Encatch.identifyUser("alice", plan("pro")) }
        settle(1_800)
        assertEquals(2, identifies.size, "one of the five goes out when the pause ends")
    }

    @Test
    fun signatures_changed_repeated_and_omitted() = runTest {
        val sig1 = IdentifyOptions(secure = SecureOptions("sig-1", "1763500000000"))
        identify("alice", options = sig1)
        identify("alice", options = sig1)
        assertEquals(1, identifies.size, "the same signature again: not sent")
        identify("alice", options = IdentifyOptions(secure = SecureOptions("sig-2", "1763500000001")))
        assertEquals(2, identifies.size, "a fresh signature: sent")
        identify("alice")
        assertEquals(3, identifies.size, "secure omitted: sent")
    }

    @Test
    fun the_same_signature_while_the_first_is_in_flight_sends_one() = runTest {
        api.reply = { endpoint, _ -> if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"uid-alice"}""", delayMs = 500) else MockApi.Reply() }
        val sig = IdentifyOptions(secure = SecureOptions("sig-1", "1763500000000"))
        repeat(5) { Encatch.identifyUser("alice", options = sig) }
        settle(900)
        assertEquals(1, identifies.size)
    }

    @Test
    fun another_user_starts_with_no_traits_or_locale_remembered() = runTest {
        identify("alice", plan("pro"), IdentifyOptions(locale = "fr"))
        identify("bob")
        identify("bob", plan("pro"))
        assertEquals(3, identifies.size, "bob's traits, the same as alice's: sent")
        identify("bob", options = IdentifyOptions(locale = "fr"))
        assertEquals(4, identifies.size, "bob's locale, the same as alice's: sent")
    }

    @Test
    fun locale_and_country_compare_with_what_the_last_identify_carried() = runTest {
        identify("alice", options = IdentifyOptions(locale = "fr"))
        identify("alice", options = IdentifyOptions(locale = "fr"))
        assertEquals(1, identifies.size, "the same locale: not sent")
        identify("alice", options = IdentifyOptions(locale = "de"))
        identify("alice", options = IdentifyOptions(locale = "de"))
        assertEquals(2, identifies.size, "different, then the same again")
        identify("alice", options = IdentifyOptions(country = "IN"))
        assertEquals(3, identifies.size, "a country the last identify didn't carry")
    }

    @Test
    fun set_locale_then_an_identify_with_that_locale_is_sent() = runTest {
        identify("alice")
        Encatch.setLocale("fr")
        identify("alice", options = IdentifyOptions(locale = "fr"))
        assertEquals(2, identifies.size)
    }

    @Test
    fun after_a_signature_expiry_the_same_identify_goes_out_once() = runTest {
        val sig = IdentifyOptions(secure = SecureOptions("sig-1", "1763500000000"))
        identify("alice", options = sig)
        api.reply = { endpoint, body ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"error":"invalid api key","reason":"user_signature_expired"}""")
                "identify-user" -> MockApi.Reply(body = """{"userId":"uid-${(body["userName"] as JsonPrimitive).content}"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("expires")
        settle(300)
        repeat(5) { Encatch.identifyUser("alice", options = sig) }
        settle(300)
        assertEquals(2, identifies.size)
    }

    @Test
    fun after_stop_session_the_identify_goes_out_and_repeats_collapse() = runTest {
        identify("alice")
        Encatch.stopSession()
        repeat(20) { Encatch.identifyUser("alice") }
        settle(300)
        assertEquals(2, identifies.size)
        assertFalse(api.settings.getStringOrNull("@encatch/session_stopped") == "true", "the identify turned the session back on")
    }

    @Test
    fun a_failed_identify_can_be_sent_again() = runTest {
        api.reply = { endpoint, _ -> if (endpoint == "identify-user") MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""") else MockApi.Reply() }
        identify("alice", plan("pro"))
        identify("alice", plan("pro"))
        assertEquals(2, identifies.size)
    }

    @Test
    fun a_dropped_identify_with_traits_forgets_them_even_after_a_later_identify_took_over() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user") {
                if (body["userAttributes"] != null) MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""", delayMs = 300)
                else MockApi.Reply(body = """{"userId":"uid-alice"}""")
            } else MockApi.Reply()
        }
        Encatch.identifyUser("alice", plan("pro")) // fails after 300 ms
        Encatch.identifyUser("alice", options = IdentifyOptions(locale = "fr")) // sent: takes over the memory
        settle(800)
        assertEquals(3, identifies.size)
        identify("alice", plan("pro"))
        assertEquals(4, identifies.size, "the failed identify's traits weren't sent, so they go again")
    }

    @Test
    fun an_earlier_identifys_drop_keeps_a_later_identifys_memory() = runTest {
        identify("alice")
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user") {
                val plan = body["userAttributes"]?.jsonObject?.get("\$set")?.jsonObject?.get("plan")
                if (plan == JsonPrimitive("bad")) MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""", delayMs = 300)
                else MockApi.Reply(body = """{"userId":"uid-alice"}""")
            } else MockApi.Reply()
        }
        Encatch.identifyUser("alice", plan("bad")) // fails after 300 ms
        Encatch.identifyUser("alice", plan("good")) // sent after it
        settle(800)
        assertEquals(3, identifies.size)
        identify("alice", plan("good"))
        identify("alice")
        assertEquals(3, identifies.size, "the later identify's memory survives the earlier one's drop")
    }

    /**
     * A caller cancelled while its identify runs: either nothing happened (cancelled before it
     * started) or the identify went out. Never memory that says "sent" with nothing queued, which
     * would skip every repeat for the rest of the run.
     */
    @OptIn(DelicateCoroutinesApi::class)
    @Test
    fun a_cancelled_caller_never_leaves_the_user_unidentifiable() = runTest(timeout = 120.seconds) {
        var stuck = 0
        withContext(Dispatchers.Default) {
            repeat(60) { i ->
                val name = "u$i"
                // other traffic, so the identify is cancelled at different points
                val noise = GlobalScope.launch(Dispatchers.Default) { repeat(40) { k -> launch { Encatch.trackEvent("e$i-$k") } } }
                val job = GlobalScope.launch(Dispatchers.Default) { Encatch.identifyUser(name, plan("x")) }
                val start = TimeSource.Monotonic.markNow()
                val spin = (60 + (i % 40) * 2).microseconds
                while (start.elapsedNow() < spin) { /* cancel at a varying point */ }
                job.cancel(); job.join(); noise.join()
                suspend fun sent(ms: Long) = withTimeoutOrNull(ms) { while (identifies.none { it.str("userName") == name }) delay(5); true } ?: false
                if (!sent(250)) {
                    Encatch.identifyUser(name, plan("x"))
                    if (!sent(1_000)) stuck++
                }
            }
        }
        assertEquals(0, stuck, "users whose identify could no longer be sent")
    }

    @Test
    fun a_skipped_call_changes_no_memory() = runTest {
        identify("alice", plan("t1"))
        identify("alice")
        identify("alice", plan("t1"))
        assertEquals(1, identifies.size)
    }

    @Test
    fun reset_and_clear_all_clear_the_memory() = runTest {
        identify("alice")
        Encatch.resetUser()
        identify("alice")
        assertEquals(2, identifies.size)
        Encatch.clearAll()
        Encatch.init("test-key")
        identify("alice")
        assertEquals(3, identifies.size)
    }

    @Test
    fun traits_that_cant_be_sent_are_ignored_and_change_no_memory() = runTest {
        identify("alice", plan("pro"))
        identify("alice", UserTraits(set = mapOf("score" to JsonPrimitive(Double.NaN))))
        identify("alice", UserTraits(increment = mapOf("n" to Double.POSITIVE_INFINITY)))
        identify("bob", UserTraits(decrement = mapOf("n" to Double.NEGATIVE_INFINITY)))
        assertEquals(1, identifies.size, "none of the three sent")
        identify("alice", plan("pro"))
        assertEquals(1, identifies.size, "the memory is still alice's plan: a repeat")
        identify("alice", UserTraits(set = mapOf("nothing" to JsonNull)))
        assertEquals(2, identifies.size, "null is a value a request can carry")
    }

    @Test
    fun the_fingerprint_helpers() {
        assertNull(traitsForDedupe(null))
        assertNull(traitsForDedupe(UserTraits(set = emptyMap(), unset = emptyList())))
        val t = traitsForDedupe(UserTraits(unset = listOf("b", "a")))!!
        assertEquals("[\"a\",\"b\"]", t["\$unset"].toString())
        assertTrue(hasUnsendableValue(UserTraits(set = mapOf("x" to kotlinx.serialization.json.buildJsonObject { put("y", JsonPrimitive(Double.NaN)) }))))
        assertFalse(hasUnsendableValue(UserTraits(set = mapOf("x" to JsonPrimitive("NaN")))))
        assertEquals(setOf("\$set"), traitsForDedupe(plan("a"))!!.jsonObject.keys)
    }
}
