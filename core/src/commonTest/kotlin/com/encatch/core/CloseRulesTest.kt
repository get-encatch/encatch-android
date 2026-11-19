package com.encatch.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Closing a survey (RN 1.4.4 items 8–10b, Flutter 1.2.0 FormSession; e2e issues AN-002, AN-003):
 * ✕ before the final submit is a dismissal — `form:dismissed` for identified users, `dismiss-form`
 * for everyone; after the final submit, "remind me later" or a call-to-action it is not.
 */
class CloseRulesTest {

    private data class Call(val endpoint: String, val body: JsonObject)

    private val calls = MutableStateFlow<List<Call>>(emptyList())
    private val sent = MutableStateFlow<List<SDKMessageType>>(emptyList())
    private val closes = MutableStateFlow(0)
    private lateinit var bridge: FormWebViewBridge

    private fun called(endpoint: String) = calls.value.filter { it.endpoint == endpoint }
    private fun events(name: String) = called("track-event").filter { it.body["eventName"]?.jsonPrimitive?.content == name }

    private val uuid = "11111111-2222-3333-4444-555555555555"

    @BeforeTest
    fun setUp() = runTest {
        calls.value = emptyList()
        sent.value = emptyList()
        closes.value = 0
        Encatch.settingsFactory = { InMemorySettings() }
        Encatch.engineFactory = {
            MockEngine { request ->
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: "{}"
                val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                calls.update { it + Call(endpoint, body) }
                val reply = if (endpoint == "identify-user") """{"userId":"user-1"}""" else "{}"
                respond(reply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        Encatch.init("test-key")
        bridge = FormWebViewBridge(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onClose = { closes.update { it + 1 } },
            onHeightChange = {},
            onForceFullHeight = {},
            onReady = {},
            sendToWebView = { m -> sent.update { it + m.type } },
            redirectOpener = RedirectOpener { },
            openExternal = { },
        )
        bridge.setFormPayload(
            ShowFormPayload(
                formId = "survey-slug",
                formConfig = ShowFormResponse(feedbackConfigurationId = uuid),
                resetMode = ResetMode.ALWAYS,
                triggerType = TriggerType.AUTOMATIC,
            ),
        )
    }

    @AfterTest
    fun tearDown() = runTest {
        Encatch.clearAll()
        Encatch.engineFactory = null
        Encatch.settingsFactory = null
    }

    private suspend fun eventually(timeoutMs: Long = 5_000, done: () -> Boolean) = withContext(Dispatchers.Default) {
        withTimeout(timeoutMs) { while (!done()) delay(20) }
    }

    private suspend fun settle(ms: Long = 300) = withContext(Dispatchers.Default) { delay(ms) }

    private suspend fun identify() {
        Encatch.identifyUser("someone")
        eventually { Encatch.userId == "user-1" }
    }

    @Test
    fun an_identified_user_s_close_before_submit_is_a_dismissal() = runTest {
        identify()
        bridge.handleMessage("""{"type":"form:ready"}""")
        bridge.handleMessage("""{"type":"form:close","formId":""}""")
        eventually { called("dismiss-form").isNotEmpty() && events("form:dismissed").isNotEmpty() }
        assertEquals(uuid, called("dismiss-form")[0].body["formConfigurationId"]?.jsonPrimitive?.content)
        assertEquals(uuid, events("form:dismissed")[0].body["feedbackConfigurationId"]?.jsonPrimitive?.content)
        assertEquals(1, closes.value)
        assertTrue(SDKMessageType.SUBMIT_PARTIAL_BEFORE_DISMISS in sent.value, "the page is asked to save a partial answer first")
    }

    @Test
    fun a_visitor_s_close_sends_dismiss_form_and_no_survey_event() = runTest {
        bridge.handleMessage("""{"type":"form:close","formId":"survey-slug"}""")
        eventually { called("dismiss-form").isNotEmpty() }
        settle()
        assertTrue(events("form:dismissed").isEmpty(), "survey events are an identified user's")
    }

    @Test
    fun the_page_saying_it_is_ready_ends_the_wait_early() = runTest {
        bridge.handleMessage("""{"type":"form:ready"}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        bridge.handleMessage("""{"type":"form:readyToDismiss"}""")
        eventually(timeoutMs = FormWebViewBridge.PARTIAL_BEFORE_DISMISS_WAIT_MS - 200) { closes.value == 1 }
    }

    @Test
    fun a_close_after_the_final_submit_is_not_a_dismissal() = runTest {
        identify()
        bridge.handleMessage("""{"type":"form:submit","data":{"isPartialSubmit":false,"feedbackConfigurationId":"$uuid","response":{"questions":[]}}}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        settle(800)
        assertTrue(called("dismiss-form").isEmpty())
        assertTrue(events("form:dismissed").isEmpty())
        assertEquals(1, closes.value)
    }

    @Test
    fun a_partial_submit_does_not_end_the_survey() = runTest {
        bridge.handleMessage("""{"type":"form:submit","data":{"isPartialSubmit":true,"feedbackConfigurationId":"$uuid","response":{"questions":[]}}}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        eventually { called("dismiss-form").isNotEmpty() }
    }

    @Test
    fun remind_me_later_is_sent_for_an_identified_user_and_is_not_a_dismissal() = runTest {
        identify()
        bridge.handleMessage("""{"type":"form:remindmelater","formId":""}""")
        eventually { events("form:remindmelater").isNotEmpty() }
        assertEquals(uuid, events("form:remindmelater")[0].body["feedbackConfigurationId"]?.jsonPrimitive?.content)
        // the page's ✕ that may follow is not a dismissal either
        bridge.handleMessage("""{"type":"form:close"}""")
        settle(800)
        assertTrue(called("dismiss-form").isEmpty())
    }

    @Test
    fun a_visitor_s_remind_me_later_sends_nothing() = runTest {
        bridge.handleMessage("""{"type":"form:remindmelater"}""")
        settle(500)
        assertTrue(called("track-event").isEmpty())
        assertEquals(1, closes.value)
    }

    @Test
    fun a_second_close_records_nothing_again() = runTest {
        bridge.handleMessage("""{"type":"form:close"}""")
        bridge.requestUserClose()
        eventually { called("dismiss-form").isNotEmpty() }
        settle(800)
        assertEquals(1, called("dismiss-form").size)
    }

    @Test
    fun a_submit_with_an_empty_form_id_uses_the_show_form_uuid() = runTest {
        bridge.handleMessage("""{"type":"form:submit","data":{"isPartialSubmit":false,"feedbackConfigurationId":"","response":{"questions":[]}}}""")
        eventually { called("submit-form").isNotEmpty() }
        val details = called("submit-form")[0].body["formDetails"]?.jsonObject
        assertEquals(uuid, details?.get("formConfigurationId")?.jsonPrimitive?.content)
    }

    @Test
    fun a_close_after_form_complete_is_not_a_dismissal() = runTest {
        bridge.handleMessage("""{"type":"form:complete","data":{"feedbackConfigurationId":"$uuid"}}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        settle(800)
        assertTrue(called("dismiss-form").isEmpty())
    }

    @Test
    fun a_call_to_action_that_closes_is_not_a_dismissal_one_that_does_not_close_changes_nothing() = runTest {
        // an action that closes nothing: the survey stays open, and a later close is still a dismissal
        bridge.handleMessage("""{"type":"form:ctaTriggered","data":{"action":"redirect_external"}}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        eventually { called("dismiss-form").isNotEmpty() }
    }

    @Test
    fun an_app_navigate_call_to_action_closes_without_a_dismissal() = runTest {
        bridge.handleMessage("""{"type":"form:ctaTriggered","data":{"action":"app_navigate"}}""")
        bridge.handleMessage("""{"type":"form:close"}""")
        settle(800)
        assertTrue(called("dismiss-form").isEmpty())
        assertTrue(closes.value >= 1)
    }

    @Test
    fun without_the_form_s_uuid_no_dismiss_form_is_sent() = runTest {
        bridge.setFormPayload(
            ShowFormPayload(
                formId = "slug-only",
                formConfig = ShowFormResponse(feedbackConfigurationId = ""),
                resetMode = ResetMode.ALWAYS,
                triggerType = TriggerType.AUTOMATIC,
            ),
        )
        bridge.handleMessage("""{"type":"form:close"}""")
        eventually { closes.value == 1 }
        settle(300)
        assertTrue(called("dismiss-form").isEmpty(), "dismiss-form needs a UUID (400 otherwise)")
    }
}
