package com.encatch.core

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.Volatile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Survey answers go only to the user they belong to (React Native handover
 * HANDOVER-ON-SIGNATURE-EXPIRED.md §6b): who the survey was shown to, else the current user; a
 * signed-in owner's answers are dropped once they are no longer current (checked again after a
 * rate-limit wait); a visitor's answers are sent whoever is current; clearAll() drops a waiting one.
 */
class SubmitOwnerTest {

    private lateinit var api: MockApi

    private class Recorder : EncatchLogger {
        val warnings = MutableStateFlow<List<String>>(emptyList())
        override fun debug(message: String) {}
        override fun warn(message: String) = warnings.update { it + message }
    }

    private lateinit var log: Recorder

    /** submit-form answers 429 (Retry-After 1 s) to its first request when set. */
    @Volatile private var firstSubmitPaused = false
    @Volatile private var submits = 0
    /** show-form answers after this long. */
    @Volatile private var showDelayMs = 0L

    @BeforeTest
    fun setUp() = runTest {
        api = MockApi()
        submits = 0
        api.replyCall = { call ->
            when (call.endpoint) {
                "identify-user" -> MockApi.Reply(body = """{"userId":"uid-${call.str("userName")}"}""")
                "show-form" -> MockApi.Reply(body = """{"feedbackConfigurationId":"fc-1","questionnaireFields":{}}""", delayMs = showDelayMs)
                "submit-form" -> if (++submits == 1 && firstSubmitPaused) {
                    MockApi.Reply(status = HttpStatusCode.TooManyRequests, headers = mapOf("Retry-After" to "1"))
                } else {
                    MockApi.Reply()
                }
                else -> MockApi.Reply()
            }
        }
        api.install()
        log = Recorder()
        Encatch.loggerFactory = { log }
        Encatch.init("test-key")
    }

    @AfterTest
    fun tearDown() = runTest { api.uninstall() }

    private suspend fun identify(name: String) {
        val before = api.called("identify-user").size
        Encatch.identifyUser(name)
        eventually { api.called("identify-user").size > before && Encatch.userId == "uid-$name" }
        settle(100)
    }

    private val shows = MutableStateFlow(0)

    private suspend fun show() {
        val before = shows.value
        val off = EncatchInternalEmitter.on { if (it is InternalEvent.ShowForm) shows.update { n -> n + 1 } }
        try {
            Encatch.showForm("form-1")
            eventually { shows.value > before }
        } finally {
            off()
        }
    }

    private suspend fun submit() =
        Encatch.submitForm(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "fc-1")))

    private val sent get() = api.called("submit-form")
    private val dropWarnings get() = log.warnings.value.filter { "A survey submit was dropped" in it }

    @Test
    fun shown_to_alice_then_a_switch_to_bob_drops_it() = runTest {
        identify("alice")
        show()
        identify("bob")
        submit()
        settle(200)
        assertTrue(sent.isEmpty(), "never sent as bob")
        assertEquals(1, dropWarnings.size)
    }

    @Test
    fun alice_submits_it_is_sent_as_alice() = runTest {
        identify("alice")
        show()
        submit()
        eventually { sent.size == 1 }
        assertEquals("alice", sent[0].header("X-User-Name"))
    }

    @Test
    fun shown_to_alice_then_resetUser_drops_it() = runTest {
        identify("alice")
        show()
        Encatch.resetUser()
        submit()
        settle(200)
        assertTrue(sent.isEmpty(), "never sent as a visitor")
    }

    @Test
    fun alice_s_submit_in_a_429_pause_then_resetUser_drops_it() = runTest {
        identify("alice")
        show()
        firstSubmitPaused = true
        launch(Dispatchers.Default) { submit() }
        eventually { sent.size == 1 } // the 429 attempt
        Encatch.resetUser()
        settle(1_500)
        assertEquals(1, sent.size, "only the 429 attempt")
        assertEquals(1, dropWarnings.size)
    }

    @Test
    fun a_visitor_shown_then_signs_in_it_is_sent() = runTest {
        show()
        identify("alice")
        submit()
        eventually { sent.size == 1 }
        assertEquals("alice", sent[0].header("X-User-Name"))
    }

    @Test
    fun a_visitor_s_submit_in_a_429_pause_is_sent_after_the_visitor_signs_in() = runTest {
        show()
        firstSubmitPaused = true
        launch(Dispatchers.Default) { submit() }
        eventually { sent.size == 1 }
        identify("alice")
        eventually(3_000) { sent.size == 2 }
        assertTrue(dropWarnings.isEmpty())
    }

    @Test
    fun the_same_survey_shown_again_to_bob_is_sent_as_bob() = runTest {
        identify("alice")
        show()
        identify("bob")
        Encatch.setFormVisible(false, "form-1") // alice's closed, then shown again for bob
        show()
        submit()
        eventually { sent.size == 1 }
        assertEquals("bob", sent[0].header("X-User-Name"))
    }

    @Test
    fun alice_bob_alice_within_a_pause_is_sent_as_alice() = runTest {
        identify("alice")
        show()
        firstSubmitPaused = true
        launch(Dispatchers.Default) { submit() }
        eventually { sent.size == 1 }
        identify("bob")
        identify("alice")
        eventually(3_000) { sent.size == 2 }
        assertEquals("alice", sent[1].header("X-User-Name"))
    }

    @Test
    fun clearAll_while_a_submit_waits_drops_it() = runTest {
        show() // a visitor's: only clearAll can drop it
        firstSubmitPaused = true
        launch(Dispatchers.Default) { submit() }
        eventually { sent.size == 1 }
        Encatch.clearAll()
        Encatch.init("test-key")
        settle(1_500)
        assertEquals(1, sent.size)
        assertTrue(log.warnings.value.any { "cleared or re-initialized while it waited" in it })
    }

    @Test
    fun a_repeated_final_submit_resolves_the_same_way() = runTest {
        identify("alice")
        show()
        submit()
        submit()
        eventually { sent.size == 2 }
        assertTrue(sent.all { it.header("X-User-Name") == "alice" })
    }

    @Test
    fun once_the_survey_closes_a_later_submit_goes_as_the_current_user() = runTest {
        identify("alice")
        show()
        Encatch.setFormVisible(false, "form-1")
        identify("bob")
        submit()
        eventually { sent.size == 1 }
        assertEquals("bob", sent[0].header("X-User-Name"))
    }

    @Test
    fun a_survey_asked_for_alice_whose_reply_arrives_after_a_switch_is_not_shown() = runTest {
        identify("alice")
        showDelayMs = 400
        val off = EncatchInternalEmitter.on { if (it is InternalEvent.ShowForm) shows.update { n -> n + 1 } }
        try {
            launch(Dispatchers.Default) { Encatch.showForm("form-1") }
            eventually { api.called("show-form").size == 1 }
            identify("bob")
            settle(600)
            assertEquals(0, shows.value)
        } finally {
            off()
        }
    }

    @Test
    fun a_visitor_s_survey_whose_reply_arrives_after_sign_in_is_still_shown() = runTest {
        showDelayMs = 400
        val off = EncatchInternalEmitter.on { if (it is InternalEvent.ShowForm) shows.update { n -> n + 1 } }
        try {
            launch(Dispatchers.Default) { Encatch.showForm("form-1") }
            eventually { api.called("show-form").size == 1 }
            identify("alice")
            eventually { shows.value == 1 }
        } finally {
            off()
        }
    }

    @Test
    fun a_re_init_while_a_submit_waits_drops_it() = runTest {
        show()
        firstSubmitPaused = true
        launch(Dispatchers.Default) { submit() }
        eventually { sent.size == 1 }
        Encatch.init("test-key")
        settle(1_500)
        assertEquals(1, sent.size)
    }

    @Test
    fun the_survey_page_s_submit_takes_its_owner_before_the_close_that_follows_it() = runTest {
        identify("alice")
        show()
        identify("bob")
        // the page sends the final submit and its close back to back
        Encatch.submitFromSurvey(SubmitFormRequest(triggerType = "manual", formDetails = FormDetails(formConfigurationId = "fc-1")))
        Encatch.setFormVisible(false, "form-1")
        settle(300)
        assertTrue(sent.isEmpty(), "alice's answers are never sent as bob")
    }
}
