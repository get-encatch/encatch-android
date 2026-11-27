package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EncatchConfig.onSignatureExpired and the narrowed expiry rules, as the React Native SDK 1.5.0-beta.3
 * (docs/HANDOVER-ON-SIGNATURE-EXPIRED.md, §8 cases 1–22). The fake gateway answers identify-user by
 * the signature it carries, as the user gateway does.
 */
class SignatureExpiredTest {

    private lateinit var api: MockApi

    private class Recorder : EncatchLogger {
        val warnings = MutableStateFlow<List<String>>(emptyList())
        override fun debug(message: String) {}
        override fun warn(message: String) = warnings.update { it + message }
    }

    private lateinit var log: Recorder

    /** Signatures the gateway rejects as expired, and as wrong (no reason). */
    @Volatile private var expired = setOf<String>()
    @Volatile private var wrong = setOf<String>()
    /** An older gateway: no reason in any 401. */
    @Volatile private var oldGateway = false
    /** An SV8-style gateway: the signature is checked on every request, not only identify-user. */
    @Volatile private var checksEveryRequest = false
    /** A 401 without the reason on track-event (a key or referer problem). */
    @Volatile private var trackEventRejected = false
    /** Delay on a rejected reply. */
    @Volatile private var rejectDelayMs = 0L
    /** Signatures whose rejected replies are slow (whatever [rejectDelayMs] is). */
    @Volatile private var slowSignatures = setOf<String>()

    private val notified = MutableStateFlow<List<String>>(emptyList())

    private fun rejected(reason: Boolean, signature: String?) = MockApi.Reply(
        status = HttpStatusCode.Unauthorized,
        body = if (reason && !oldGateway) """{"error":"invalid api key","reason":"user_signature_expired"}""" else """{"error":"invalid api key"}""",
        delayMs = if (signature != null && signature in slowSignatures) 600 else rejectDelayMs,
    )

    private fun gateway(call: MockApi.Call): MockApi.Reply {
        val signature = call.header("X-User-Signature")
        fun rejected(reason: Boolean) = rejected(reason, signature)
        if (call.endpoint == "identify-user") {
            if (signature != null && signature in expired) return rejected(reason = true)
            if (signature != null && signature in wrong) return rejected(reason = false)
            return MockApi.Reply(body = """{"userId":"uid-${call.str("userName")}"}""")
        }
        if (checksEveryRequest && signature != null && signature in expired) return rejected(reason = true)
        if (call.endpoint == "track-event" && trackEventRejected) return rejected(reason = false)
        return MockApi.Reply()
    }

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        api.replyCall = { gateway(it) }
        api.install()
        log = Recorder()
        Encatch.loggerFactory = { log }
    }

    @AfterTest
    fun tearDown() = runTest {
        Encatch.signatureExpiredCallDelayMs = 0
        api.uninstall()
    }

    /** init with a callback that records the user and then runs [then]. */
    private suspend fun start(then: (suspend (String) -> Unit)? = null, configured: Boolean = true) {
        Encatch.init(
            "test-key",
            EncatchConfig(
                onSignatureExpired = if (!configured) null else { info ->
                    notified.update { it + info.userName }
                    then?.invoke(info.userName)
                },
            ),
        )
    }

    private fun secure(signature: String, time: String? = "1763500000000") = IdentifyOptions(secure = SecureOptions(signature, time))

    private suspend fun identify(name: String, signature: String? = null, traits: UserTraits? = null, time: String? = "1763500000000") {
        val before = api.called("identify-user").size
        Encatch.identifyUser(name, traits, options = signature?.let { secure(it, time) })
        eventually { api.called("identify-user").size > before }
        settle(200)
    }

    private suspend fun update(name: String = "alice") {
        val before = api.called("identify-user").size
        Encatch.setUserProperties(name, UserTraits(set = mapOf("plan" to JsonPrimitive("pro"))))
        eventually { api.called("identify-user").size > before }
        settle(200)
    }

    private suspend fun eventSent(name: String): Boolean {
        val before = api.called("track-event").size
        Encatch.trackEvent(name)
        settle(300)
        return api.called("track-event").size > before
    }

    private suspend fun submit() = Encatch.submitForm(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "form-1")))

    private val expiryWarnings
        get() = log.warnings.value.filter { "the secure signature has expired" in it || "Secure identify signature expired" in it }

    private fun storedSignature(name: String = "alice") = api.settings.getStringOrNull("@encatch/user_signature_$name")

    // 1
    @Test
    fun a_stored_signature_found_expired_by_setUserProperties_calls_once_and_holds_submits_until_a_fresh_identify() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        update()
        eventually { notified.value == listOf("alice") }
        assertTrue(!eventSent("skipped"), "the expired state skips events")
        submit()
        settle(200)
        assertTrue(api.called("submit-form").isEmpty(), "held")
        identify("alice", "sig-2")
        eventually { api.called("submit-form").size == 1 }
        assertEquals("sig-2", api.called("submit-form")[0].header("X-User-Signature"))
        settle(200)
        assertEquals(listOf("alice"), notified.value, "no second call")
    }

    // 2
    @Test
    fun identify_with_an_already_expired_signature_calls_once_without_the_expired_state() = runTest {
        start()
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        eventually { notified.value == listOf("alice") }
        assertTrue(eventSent("still-sent"), "not in the expired state")
        assertEquals(1, expiryWarnings.size, expiryWarnings.toString())
    }

    // 3
    @Test
    fun a_stale_signature_for_the_same_user_leaves_the_valid_stored_one() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-stale")
        identify("alice", "sig-stale")
        eventually { notified.value == listOf("alice") }
        assertTrue(storedSignature()!!.contains("sig-1"), "storage unchanged")
        assertTrue(eventSent("after"))
        assertEquals("sig-1", api.called("track-event").last().header("X-User-Signature"), "memory unchanged")
    }

    // 4
    @Test
    fun a_switch_with_a_stale_signature_calls_for_the_new_user_and_tracking_goes_on() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-bob-stale")
        identify("bob", "sig-bob-stale")
        eventually { notified.value == listOf("bob") }
        assertTrue(eventSent("after"))
    }

    // 5
    @Test
    fun a_new_signature_that_keeps_being_rejected_calls_once_then_again_after_a_success() = runTest {
        start(then = { name -> Encatch.identifyUser(name, options = secure("sig-bad-again")) })
        expired = setOf("sig-bad", "sig-bad-again")
        identify("alice", "sig-bad")
        eventually { notified.value.size == 1 }
        settle(800)
        assertEquals(listOf("alice"), notified.value, "no loop")
        assertEquals(2, api.called("identify-user").size, "the callback's identify, then nothing more")
        identify("alice", "sig-good")
        expired = expired + "sig-good"
        update()
        eventually { notified.value.size == 2 }
    }

    // 6
    @Test
    fun the_app_identifying_again_from_the_callback_after_a_delay_sends_the_held_submit_and_ends_the_state() = runTest {
        start(then = { name ->
            delay(300) // a fetch from the app's server
            Encatch.identifyUser(name, options = secure("sig-fresh"))
        })
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        update()
        eventually { notified.value.size == 1 } // the app is fetching a fresh signature
        submit()
        assertTrue(api.called("submit-form").isEmpty(), "held while the app fetches")
        eventually { api.called("submit-form").size == 1 }
        assertEquals("sig-fresh", api.called("submit-form")[0].header("X-User-Signature"))
        assertTrue(eventSent("state-ended"))
    }

    // 7
    @Test
    fun called_once_per_expiry() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-1", "sig-2")
        update()
        eventually { notified.value.size == 1 }
        identify("alice", "sig-2") // a fresh signature still rejected
        settle(300)
        assertEquals(1, notified.value.size)
        identify("alice", "sig-3")
        expired = expired + "sig-3"
        update()
        eventually { notified.value.size == 2 }
    }

    // 8
    @Test
    fun an_older_gateway_without_the_reason_still_counts_on_setUserProperties() = runTest {
        start()
        identify("alice", "sig-1")
        oldGateway = true
        expired = setOf("sig-1")
        update()
        eventually { notified.value == listOf("alice") }
        assertTrue(!eventSent("skipped"), "the expired state")
    }

    // 9
    @Test
    fun a_401_without_the_reason_on_another_request_is_not_an_expiry() = runTest {
        start()
        identify("alice", "sig-1")
        trackEventRejected = true
        Encatch.trackEvent("rejected")
        eventually { api.called("track-event").size == 1 }
        settle(200)
        trackEventRejected = false
        assertTrue(eventSent("next"))
        assertEquals("sig-1", api.called("track-event").last().header("X-User-Signature"))
        assertTrue(notified.value.isEmpty())
        assertTrue(storedSignature()!!.contains("sig-1"))
    }

    // 10
    @Test
    fun an_identify_rejected_without_the_reason_is_a_wrong_signature() = runTest {
        start()
        wrong = setOf("sig-wrong")
        identify("alice", "sig-wrong")
        settle(200)
        assertTrue(notified.value.isEmpty())
        assertTrue(expiryWarnings.isEmpty())
        assertTrue(eventSent("after"))
    }

    // 11
    @Test
    fun the_reason_on_a_non_identify_request_counts() = runTest {
        start()
        identify("alice", "sig-1")
        checksEveryRequest = true
        expired = setOf("sig-1")
        Encatch.trackEvent("rejected")
        eventually { notified.value == listOf("alice") }
        settle(200)
        checksEveryRequest = false
        assertTrue(!eventSent("skipped"), "the expired state")
    }

    // 12
    @Test
    fun an_expiry_reply_for_a_user_reset_since_is_ignored() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        rejectDelayMs = 400
        launch(Dispatchers.Default) { Encatch.setUserProperties("alice", UserTraits(set = mapOf("plan" to JsonPrimitive("pro")))) }
        eventually { api.called("identify-user").size == 2 }
        Encatch.resetUser()
        settle(800)
        assertTrue(notified.value.isEmpty())
        assertTrue(eventSent("as-visitor"))
    }

    // 13
    @Test
    fun a_late_expiry_reply_for_a_replaced_signature_is_ignored() = runTest {
        start()
        identify("alice", "sig-1")
        checksEveryRequest = true
        expired = setOf("sig-1")
        slowSignatures = setOf("sig-1")
        launch(Dispatchers.Default) { Encatch.trackFormEvent("form:show", "f-1") } // not queued
        eventually { api.called("track-event").size == 1 }
        identify("alice", "sig-2")
        settle(800) // the late 401 for sig-1 has arrived
        assertTrue(notified.value.isEmpty())
        assertTrue(eventSent("after"))
        assertEquals("sig-2", api.called("track-event").last().header("X-User-Signature"))
    }

    // 14
    @Test
    fun clearAll_while_an_expiry_reply_is_on_its_way_means_no_call() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        rejectDelayMs = 400
        launch(Dispatchers.Default) { Encatch.setUserProperties("alice", UserTraits(set = mapOf("plan" to JsonPrimitive("pro")))) }
        eventually { api.called("identify-user").size == 2 }
        Encatch.clearAll()
        settle(800)
        assertTrue(notified.value.isEmpty())
    }

    // 15
    @Test
    fun a_callback_that_throws_leaves_the_sdk_working() = runTest {
        start(then = { throw IllegalStateException("app bug") })
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        eventually { notified.value.size == 1 }
        settle(200)
        identify("alice", "sig-good")
        eventually { Encatch.userId == "uid-alice" }
        assertTrue(log.warnings.value.any { "onSignatureExpired threw" in it })
    }

    // 16
    @Test
    fun without_a_callback_it_behaves_as_before() = runTest {
        start(configured = false)
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        update()
        settle(200)
        assertEquals(1, expiryWarnings.size, expiryWarnings.toString())
        assertTrue(!eventSent("skipped"), "waits for a fresh identify")
        identify("alice", "sig-2")
        assertTrue(eventSent("after"))
    }

    // 17
    @Test
    fun told_about_a_stale_new_signature_then_the_stored_one_expires_told_again() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-stale")
        identify("alice", "sig-stale")
        eventually { notified.value.size == 1 }
        expired = setOf("sig-stale", "sig-1")
        update()
        eventually { notified.value.size == 2 }
        assertTrue(!eventSent("skipped"), "the expired state, never entered silently")
    }

    // 18
    @Test
    fun an_identify_resending_the_stored_signature_rejected_as_expired_enters_the_expired_state() = runTest {
        start()
        identify("alice", "sig-1")
        expired = setOf("sig-1")
        identify("alice", "sig-1", traits = UserTraits(set = mapOf("plan" to JsonPrimitive("team"))))
        eventually { notified.value == listOf("alice") }
        assertEquals(null, storedSignature(), "the stored signature is cleared")
        assertTrue(!eventSent("skipped"), "the expired state")
    }

    // 19
    @Test
    fun resetUser_lets_it_be_called_again_for_the_same_user() = runTest {
        start()
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        eventually { notified.value.size == 1 }
        Encatch.resetUser()
        identify("alice", "sig-old")
        eventually { notified.value.size == 2 }
    }

    // 20
    @Test
    fun clearAll_removes_the_callback_and_resets_the_once_only_rule() = runTest {
        start()
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        eventually { notified.value.size == 1 }
        Encatch.clearAll()
        Encatch.init("test-key")
        identify("alice", "sig-old")
        settle(300)
        assertEquals(1, notified.value.size, "init without the option: not called")
        Encatch.clearAll()
        start()
        identify("alice", "sig-old")
        eventually { notified.value.size == 2 }
    }

    // 21
    @Test
    fun stop_between_the_reply_and_the_deferred_call_means_no_call() = runTest {
        start()
        Encatch.signatureExpiredCallDelayMs = 500
        expired = setOf("sig-old")
        identify("alice", "sig-old") // the reply is handled; the call waits
        Encatch.stop()
        settle(700)
        assertTrue(notified.value.isEmpty())
    }

    // 22
    @Test
    fun with_a_key_without_a_session_time_a_401_without_the_reason_on_setUserProperties_is_not_an_expiry() = runTest {
        start()
        identify("alice", "sig-1", time = null)
        oldGateway = true
        expired = setOf("sig-1")
        update()
        settle(200)
        assertTrue(notified.value.isEmpty())
        assertTrue(eventSent("still-sent"))
    }

    @Test
    fun an_identify_expiry_reply_after_a_reset_is_ignored_with_no_warning() = runTest {
        start()
        expired = setOf("sig-old")
        rejectDelayMs = 400
        launch(Dispatchers.Default) { Encatch.identifyUser("alice", options = secure("sig-old")) }
        eventually { api.called("identify-user").size == 1 }
        Encatch.resetUser()
        settle(800)
        assertTrue(notified.value.isEmpty())
        assertTrue(expiryWarnings.isEmpty(), expiryWarnings.toString())
    }

    @Test
    fun after_a_successful_identify_a_rejected_new_signature_is_told_again() = runTest {
        start()
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        eventually { notified.value.size == 1 }
        identify("alice", "sig-good")
        identify("alice", "sig-old")
        eventually { notified.value.size == 2 }
    }

    @Test
    fun the_deferred_call_still_comes_when_nothing_changes_meanwhile() = runTest {
        start()
        Encatch.signatureExpiredCallDelayMs = 500
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        assertTrue(notified.value.isEmpty(), "not yet")
        eventually { notified.value == listOf("alice") }
    }

    @Test
    fun a_switch_or_a_re_init_before_the_deferred_call_means_no_call() = runTest {
        start()
        Encatch.signatureExpiredCallDelayMs = 500
        expired = setOf("sig-old")
        identify("alice", "sig-old")
        identify("bob")
        settle(600)
        assertTrue(notified.value.isEmpty(), "another user is current")
        Encatch.resetUser()
        identify("alice", "sig-old")
        start() // re-init: the same user is restored
        settle(700)
        assertTrue(notified.value.isEmpty(), "re-initialized meanwhile")
    }

    @Test
    fun a_submit_rejected_as_expired_is_held_and_sent_after_a_fresh_identify() = runTest {
        start()
        identify("alice", "sig-1")
        checksEveryRequest = true
        expired = setOf("sig-1")
        submit()
        eventually { notified.value == listOf("alice") }
        assertEquals(1, api.called("submit-form").size)
        identify("alice", "sig-2")
        eventually { api.called("submit-form").size == 2 }
        assertEquals("sig-2", api.called("submit-form")[1].header("X-User-Signature"))
    }
}
