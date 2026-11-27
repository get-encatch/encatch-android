package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RN 1.5.0 items 2 and 4: `isIdentified()` and `EncatchConfig.persistUserAcrossRestarts`
 * (react-native-sdk docs/SDK-1.5.0-CHANGES-FOR-OTHER-SDKS.md). A "restart" is a new app run: the
 * in-memory user store is forgotten and the SDK initialised again on the same device storage.
 */
class IdentifiedAndPersistTest {

    private lateinit var api: MockApi
    private val memoryOnly = EncatchConfig(persistUserAcrossRestarts = false)
    private val secure = IdentifyOptions(secure = SecureOptions(signature = "sig-abc", generatedDateTimeInUtc = "1763500000000"))

    @BeforeTest
    fun setUp() {
        api = MockApi()
        api.install()
    }

    @AfterTest
    fun tearDown() = runTest {
        api.uninstall()
        Encatch.forgetAppRunForTest()
    }

    private suspend fun identified(name: String, userId: String = "user-1", options: IdentifyOptions? = null) {
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"$userId"}""") else MockApi.Reply()
        }
        val before = api.called("identify-user").size
        Encatch.identifyUser(name, options = options)
        eventually { api.called("identify-user").size > before && Encatch.userName == name && Encatch.userId == userId }
        settle(150)
    }

    private suspend fun restart(config: EncatchConfig? = null) {
        Encatch.forgetAppRunForTest()
        Encatch.init("test-key", config)
    }

    private fun userKeysOnDevice() = api.settings.keys.filter {
        it == "@encatch/user_name" || it.startsWith("@encatch/user_id_") ||
            it.startsWith("@encatch/user_signature_") || it.startsWith("@encatch/ft_")
    }

    private suspend fun lastTrackEvent(name: String): MockApi.Call {
        Encatch.trackEvent(name)
        eventually { api.called("track-event").any { it.str("eventName") == name || it.body.toString().contains(name) } }
        return api.called("track-event").last()
    }

    // ---- isIdentified() ------------------------------------------------------------------------------

    @Test
    fun not_identified_before_init() {
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun a_visitor_is_not_identified() = runTest {
        Encatch.init("test-key")
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun identified_after_an_identify_succeeds() = runTest {
        Encatch.init("test-key")
        identified("alice")
        assertTrue(Encatch.isIdentified())
    }

    @Test
    fun a_returning_user_is_identified_as_soon_as_init_finishes() = runTest {
        Encatch.init("test-key")
        identified("alice")
        restart()
        assertTrue(Encatch.isIdentified())
        assertEquals("alice", Encatch.userName)
    }

    @Test
    fun a_saved_name_without_an_id_is_not_identified() = runTest {
        api.settings.putString("@encatch/user_name", "half")
        Encatch.init("test-key")
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun a_new_user_is_not_identified_while_the_first_identify_is_pending() = runTest {
        Encatch.init("test-key")
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"user-1"}""", delayMs = 800) else MockApi.Reply()
        }
        Encatch.identifyUser("alice")
        eventually { api.called("identify-user").isNotEmpty() }
        assertFalse(Encatch.isIdentified())
        eventually { Encatch.userId == "user-1" }
        assertTrue(Encatch.isIdentified())
    }

    @Test
    fun a_new_user_whose_first_identify_failed_is_not_identified() = runTest {
        Encatch.init("test-key")
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""") else MockApi.Reply()
        }
        Encatch.identifyUser("alice")
        eventually { api.called("identify-user").isNotEmpty() }
        settle()
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun switching_users_keeps_the_previous_user_until_the_new_identify_succeeds() = runTest {
        Encatch.init("test-key")
        identified("alice", "user-a")
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"user-b"}""", delayMs = 800) else MockApi.Reply()
        }
        Encatch.identifyUser("bob")
        eventually { api.called("identify-user").size == 2 }
        // pending: still alice's confirmed identity
        assertTrue(Encatch.isIdentified())
        eventually { Encatch.userId == "user-b" }
        assertTrue(Encatch.isIdentified())
        assertEquals("bob", Encatch.userName)
    }

    @Test
    fun switching_users_keeps_the_previous_user_when_the_new_identify_fails() = runTest {
        Encatch.init("test-key")
        identified("alice", "user-a")
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(status = HttpStatusCode.BadRequest, body = """{"message":"bad"}""") else MockApi.Reply()
        }
        Encatch.identifyUser("bob")
        eventually { api.called("identify-user").size == 2 }
        settle()
        assertTrue(Encatch.isIdentified())
    }

    @Test
    fun reset_and_clear_all_end_the_identity() = runTest {
        Encatch.init("test-key")
        identified("alice")
        Encatch.resetUser()
        assertFalse(Encatch.isIdentified())

        identified("bob", "user-b")
        assertTrue(Encatch.isIdentified())
        Encatch.clearAll()
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun the_servers_retries_exhausted_reset_ends_the_identity() = runTest {
        Encatch.init("test-key")
        identified("alice")
        api.reply = { endpoint, _ ->
            if (endpoint == "track-event") MockApi.Reply(body = """{"pingAgainIn":900,"userPendingRetryExhausted":true}""") else MockApi.Reply()
        }
        Encatch.trackEvent("trigger")
        eventually { Encatch.userName == null }
        assertFalse(Encatch.isIdentified())
    }

    @Test
    fun stop_session_and_an_expired_signature_leave_it_unchanged() = runTest {
        Encatch.init("test-key")
        identified("alice", options = secure)
        Encatch.stopSession()
        assertTrue(Encatch.isIdentified())
        Encatch.startSession()
        api.reply = { endpoint, _ ->
            if (endpoint == "track-event") MockApi.Reply(status = HttpStatusCode.Unauthorized, body = """{"error":"invalid api key","reason":"user_signature_expired"}""") else MockApi.Reply()
        }
        Encatch.trackEvent("expires")
        eventually { api.called("track-event").isNotEmpty() }
        settle()
        assertTrue(Encatch.isIdentified())
    }

    // ---- persistUserAcrossRestarts = false ------------------------------------------------------------

    @Test
    fun memory_only_saves_nothing_about_the_user_but_requests_carry_the_identity() = runTest {
        Encatch.init("test-key", memoryOnly)
        identified("alice", options = secure)
        assertEquals(emptyList(), userKeysOnDevice())
        assertTrue(Encatch.isIdentified())

        val call = lastTrackEvent("as_alice")
        assertEquals("alice", call.header("X-User-Name"))
        assertEquals("user-1", call.header("X-User-Id"))
        assertEquals("sig-abc", call.header("X-User-Signature"))
    }

    @Test
    fun memory_only_starts_the_next_launch_as_a_visitor() = runTest {
        Encatch.init("test-key", memoryOnly)
        identified("alice", options = secure)
        restart(memoryOnly)

        assertFalse(Encatch.isIdentified())
        assertNull(Encatch.userName)
        val call = lastTrackEvent("after_restart")
        assertNull(call.header("X-User-Name"))
        assertNull(call.header("X-User-Id"))
        assertNull(call.header("X-User-Signature"))
    }

    @Test
    fun memory_only_deletes_user_data_an_earlier_run_saved() = runTest {
        Encatch.init("test-key")
        identified("alice", options = secure)
        assertTrue(userKeysOnDevice().isNotEmpty())

        restart(memoryOnly)
        assertEquals(emptyList(), userKeysOnDevice())
        assertFalse(Encatch.isIdentified())
        assertNull(Encatch.userName)
    }

    @Test
    fun memory_only_still_saves_the_device_id_a_stop_and_preferences() = runTest {
        Encatch.init("test-key", memoryOnly)
        val deviceId = Encatch.deviceId
        // locale and country through identify: both saved straight away
        identified("alice", options = IdentifyOptions(locale = "fr", country = "FR"))
        Encatch.stopSession()

        assertEquals(deviceId, api.settings.getStringOrNull("@encatch/device_id"))
        assertEquals("true", api.settings.getStringOrNull("@encatch/session_stopped"))
        val prefs = api.settings.getStringOrNull("@encatch/preferences")
        assertNotNull(prefs)
        assertTrue(prefs.contains("\"fr\"") && prefs.contains("\"FR\""), prefs)

        restart(memoryOnly)
        assertEquals(deviceId, Encatch.deviceId)
    }

    @Test
    fun memory_only_reuses_an_earlier_users_id_within_the_same_run() = runTest {
        Encatch.init("test-key", memoryOnly)
        identified("alice", "user-a")
        identified("bob", "user-b")
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"user-a"}""", delayMs = 800) else MockApi.Reply()
        }
        Encatch.identifyUser("alice")
        // straight away, before the reply: alice's id from this run
        eventually { Encatch.userName == "alice" }
        assertEquals("user-a", Encatch.userId)
        assertEquals(emptyList(), userKeysOnDevice())
        settle(1_000) // the slow reply lands here, not in the next test
    }

    @Test
    fun memory_only_clear_all_forgets_the_in_memory_user() = runTest {
        Encatch.init("test-key", memoryOnly)
        identified("alice", "user-a")
        Encatch.clearAll()
        Encatch.init("test-key", memoryOnly)
        assertNull(Encatch.userName)
        assertFalse(Encatch.isIdentified())
        // and the stored id is gone too: identifying alice again starts without it
        api.reply = { endpoint, _ ->
            if (endpoint == "identify-user") MockApi.Reply(body = """{"userId":"user-a"}""", delayMs = 800) else MockApi.Reply()
        }
        Encatch.identifyUser("alice")
        eventually { Encatch.userName == "alice" }
        assertNull(Encatch.userId)
        settle(1_000) // the slow reply lands here, not in the next test
    }

    @Test
    fun by_default_the_user_is_restored_after_a_restart() = runTest {
        Encatch.init("test-key")
        identified("alice", options = secure)
        restart()
        assertTrue(Encatch.isIdentified())
        val call = lastTrackEvent("restored")
        assertEquals("alice", call.header("X-User-Name"))
        assertEquals("user-1", call.header("X-User-Id"))
        assertEquals("sig-abc", call.header("X-User-Signature"))
    }
}
