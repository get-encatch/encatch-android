package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Identity and secure mode — RN 1.4.4 items 3 and 11–17: both-or-neither identity headers,
 * identify-user's own identity, reset/identify ordering, "retries exhausted", the secure signature
 * on every request with its expiry, masking in logs, identical identifies skipped.
 */
class IdentityRulesTest {

    private lateinit var api: MockApi

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.install()
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest {
        Encatch.onNetworkLog = null
        api.uninstall()
    }

    private val secure = IdentifyOptions(secure = SecureOptions(signature = "sig-abc", generatedDateTimeInUtc = "1763500000000"))

    private suspend fun identified(name: String = "alice", options: IdentifyOptions? = null) {
        val before = api.called("identify-user").size
        Encatch.identifyUser(name, options = options)
        eventually { api.called("identify-user").size > before && Encatch.userId != null && Encatch.userName == name }
        settle(150)
    }

    @Test
    fun a_half_identified_device_goes_as_a_visitor() = runTest {
        // left by an older SDK: a user name stored, no id
        api.settings.putString("@encatch/user_name", "half")
        Encatch.init("test-key")
        Encatch.trackEvent("after_upgrade")
        eventually { api.called("track-event").isNotEmpty() }
        val call = api.called("track-event").single()
        assertNull(call.header("X-User-Name"))
        assertNull(call.header("X-User-Id"))
    }

    @Test
    fun an_identified_user_sends_both_headers() = runTest {
        identified()
        Encatch.trackEvent("both")
        eventually { api.called("track-event").isNotEmpty() }
        val call = api.called("track-event").single()
        assertEquals("alice", call.header("X-User-Name"))
        assertEquals("user-1", call.header("X-User-Id"))
    }

    @Test
    fun identify_sends_its_own_name_and_never_a_user_id() = runTest {
        identified("alice")
        // switching to a new user: no id is known for bob, none goes in the body or the headers
        Encatch.identifyUser("bob")
        eventually { api.called("identify-user").size == 2 }
        val bob = api.called("identify-user")[1]
        assertEquals("bob", bob.header("X-User-Name"))
        assertEquals("bob", bob.str("userName"))
        assertNull(bob.header("X-User-Id"))
        assertNull(bob.body["userId"], "alice's id never goes with bob")
        settle(200)
        // the same user again, other traits: the body carries the id known for that name, the headers still don't
        Encatch.identifyUser("bob", UserTraits(set = mapOf("plan" to JsonPrimitive("pro"))))
        eventually { api.called("identify-user").size == 3 }
        val again = api.called("identify-user")[2]
        assertEquals("user-1", again.str("userId"))
        assertNull(again.header("X-User-Id"))
        assertEquals("bob", again.header("X-User-Name"))
    }

    @Test
    fun queued_identifies_keep_their_own_name() = runTest {
        api.reply = { endpoint, body ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"id-${body["userName"]?.jsonPrimitive?.content}"}""", delayMs = 150)
            else MockApi.Reply()
        }
        Encatch.identifyUser("first")
        Encatch.identifyUser("second")
        Encatch.identifyUser("third")
        eventually { api.called("identify-user").size == 3 }
        api.called("identify-user").forEach { assertEquals(it.str("userName"), it.header("X-User-Name")) }
        eventually { Encatch.userId == "id-third" }
        assertEquals("third", Encatch.userName)
    }

    @Test
    fun a_late_identify_reply_for_a_user_no_longer_current_is_ignored() = runTest {
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"late-id","pingAgainIn":0.2}""", delayMs = 400)
            else MockApi.Reply()
        }
        Encatch.identifyUser("late")
        eventually { api.called("identify-user").isNotEmpty() }
        Encatch.resetUser()
        settle(1_000)
        assertNull(Encatch.userName)
        assertNull(Encatch.userId)
        assertNull(api.settings.getStringOrNull("@encatch/user_id_late"), "its id is not stored")
        assertTrue(api.called("ping").isEmpty(), "no session started for it")
    }

    @Test
    fun reset_and_identify_run_one_after_the_other() = runTest {
        identified("old")
        withContext(Dispatchers.Default) {
            // neither awaited: the reset can't wipe the identify that follows it
            launch(start = CoroutineStart.UNDISPATCHED) { Encatch.resetUser() }
            launch(start = CoroutineStart.UNDISPATCHED) { Encatch.identifyUser("new") }
        }
        eventually { Encatch.userName == "new" && Encatch.userId != null }
        val last = api.called("identify-user").last()
        assertEquals("new", last.header("X-User-Name"))
        assertEquals(2, api.called("identify-user").size)
    }

    @Test
    fun retries_exhausted_in_camel_case_resets_the_user() = runTest {
        identified()
        api.reply = { endpoint, _ ->
            if (endpoint == "track-event") MockApi.Reply(body = """{"pingAgainIn":900,"userPendingRetryExhausted":true}""")
            else MockApi.Reply()
        }
        Encatch.trackEvent("pending")
        eventually { Encatch.userName == null }
        assertNull(Encatch.userId)
    }

    @Test
    fun secure_identify_sends_the_signature_header_and_both_time_headers() = runTest {
        Encatch.identifyUser("alice", options = secure)
        eventually { api.called("identify-user").isNotEmpty() }
        val call = api.called("identify-user").single()
        assertEquals("sig-abc", call.header("X-User-Signature"))
        assertEquals("1763500000000", call.header("X-User-Session-Time"))
        assertEquals("1763500000000", call.header("X-User-Signature-Time"))
        assertEquals("sig-abc", call.str("userSignature"))
        assertNull(call.header("X-User-Id"))
    }

    @Test
    fun after_a_secure_identify_the_signature_goes_on_every_request_and_survives_a_restart() = runTest {
        identified("alice", secure)
        Encatch.trackEvent("signed")
        eventually { api.called("track-event").size == 1 }
        api.called("track-event")[0].let {
            assertEquals("sig-abc", it.header("X-User-Signature"))
            assertEquals("1763500000000", it.header("X-User-Session-Time"))
            assertEquals("1763500000000", it.header("X-User-Signature-Time"))
            assertEquals("user-1", it.header("X-User-Id"))
        }
        // a restart: the same storage, a new init
        Encatch.init("test-key")
        Encatch.trackEvent("after_restart")
        eventually { api.called("track-event").size == 2 }
        assertEquals("sig-abc", api.called("track-event")[1].header("X-User-Signature"))
    }

    @Test
    fun an_identify_without_secure_clears_the_signature_and_reset_clears_it_too() = runTest {
        identified("alice", secure)
        Encatch.identifyUser("alice")
        eventually { api.called("identify-user").size == 2 }
        settle(200)
        Encatch.trackEvent("unsigned")
        eventually { api.called("track-event").size == 1 }
        assertNull(api.called("track-event")[0].header("X-User-Signature"))
        assertNull(api.settings.getStringOrNull("@encatch/user_signature_alice"))

        identified("bob", secure)
        Encatch.resetUser()
        Encatch.trackEvent("visitor")
        eventually { api.called("track-event").size == 2 }
        val visitor = api.called("track-event")[1]
        assertNull(visitor.header("X-User-Signature"), "never on a visitor's request")
        assertNull(visitor.header("X-User-Session-Time"))
        assertNull(api.settings.getStringOrNull("@encatch/user_signature_bob"))
    }

    @Test
    fun an_expired_signature_pauses_background_traffic_and_holds_submits_until_the_next_identify() = runTest {
        identified("alice", secure)
        api.reply = { endpoint, _ ->
            when (endpoint) {
                "track-event" -> MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"message":"signature expired"}""")
                "identify-user" -> MockApi.Reply(body = """{"userId":"user-1"}""")
                else -> MockApi.Reply()
            }
        }
        Encatch.trackEvent("expires")
        eventually { api.called("track-event").size == 1 }
        settle(200)
        Encatch.trackEvent("skipped")
        Encatch.trackScreen("Skipped")
        Encatch.startSession() // its immediate ping is skipped too
        Encatch.submitForm(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "form-1")))
        settle(500)
        assertEquals(1, api.called("track-event").size)
        assertTrue(api.called("track-screen").isEmpty())
        assertTrue(api.called("ping").isEmpty())
        assertTrue(api.called("submit-form").isEmpty(), "held")
        assertNull(api.settings.getStringOrNull("@encatch/user_signature_alice"), "the stored signature is dropped")

        Encatch.identifyUser("alice", options = IdentifyOptions(secure = SecureOptions("sig-new", "1763600000000")))
        eventually { api.called("submit-form").size == 1 }
        assertEquals("sig-new", api.called("submit-form")[0].header("X-User-Signature"))
        Encatch.trackScreen("Back")
        eventually { api.called("track-screen").size == 1 }
    }

    @Test
    fun the_signature_is_masked_in_the_network_log() = runTest {
        val logs = MutableStateFlow<List<EncatchNetworkLogEntry>>(emptyList())
        Encatch.onNetworkLog = { entry -> logs.update { it + entry } }
        Encatch.init("test-key", EncatchConfig(debugMode = true))
        identified("alice", secure)
        Encatch.trackEvent("signed")
        eventually { api.called("track-event").isNotEmpty() }
        settle(200)
        val entries = logs.value
        val identify = entries.first { it.endpoint == Endpoints.IDENTIFY_USER }
        assertEquals("***", identify.requestHeaders["X-User-Signature"])
        assertFalse(identify.requestBody.contains("sig-abc"), identify.requestBody)
        assertTrue(identify.requestBody.contains("\"userSignature\":\"***\""))
        val event = entries.first { it.endpoint == Endpoints.TRACK_EVENT }
        assertEquals("***", event.requestHeaders["X-User-Signature"])
        assertTrue(event.requestHeaders["X-Api-Key"]!!.startsWith("•••"))
        entries.forEach { e -> e.requestHeaders.values.forEach { assertFalse(it.contains("sig-abc")) } }
    }

    @Test
    fun identical_identifies_are_sent_once() = runTest {
        repeat(5) { Encatch.identifyUser("dup", UserTraits(set = mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("2")))) }
        settle(600)
        assertEquals(1, api.called("identify-user").size)
        // the same traits in another order: still the same identify
        Encatch.identifyUser("dup", UserTraits(set = mapOf("b" to JsonPrimitive("2"), "a" to JsonPrimitive("1"))))
        // other traits: sent
        Encatch.identifyUser("dup", UserTraits(set = mapOf("a" to JsonPrimitive("3"))))
        eventually { api.called("identify-user").size == 2 }
        settle(300)
        assertEquals(2, api.called("identify-user").size)
        // after resetUser the same identify is sent again
        Encatch.resetUser()
        Encatch.identifyUser("dup", UserTraits(set = mapOf("a" to JsonPrimitive("3"))))
        eventually { api.called("identify-user").size == 3 }
    }

    @Test
    fun a_dropped_identify_is_forgotten_so_the_same_one_can_be_sent_again() = runTest {
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""") else MockApi.Reply()
        }
        Encatch.identifyUser("retry")
        eventually { api.called("identify-user").size == 1 }
        settle(300)
        api.reply = { endpoint, _ -> if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"user-1"}""") else MockApi.Reply() }
        Encatch.identifyUser("retry")
        eventually { api.called("identify-user").size == 2 }
        eventually { Encatch.userId == "user-1" }
    }

    @Test
    fun stable_json_key_ignores_key_order() {
        val a = JsonObject(mapOf("x" to JsonPrimitive(1), "y" to JsonObject(mapOf("b" to JsonPrimitive("2"), "a" to JsonPrimitive("1")))))
        val b = JsonObject(mapOf("y" to JsonObject(mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("2"))), "x" to JsonPrimitive(1)))
        assertEquals(stableJsonKey(a), stableJsonKey(b))
        assertNotNull(stableJsonKey(a))
    }
}
