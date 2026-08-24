package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FormWebViewBridgeTest {

    private val sentMessages = mutableListOf<SDKMessage>()
    private var closedImmediate: Boolean? = null
    private var heightChanged: Int? = null
    private var fullHeightForced: Boolean? = null
    private var readyCalled = false

    private lateinit var bridge: FormWebViewBridge
    private val testEventCallbacks = mutableListOf<Pair<EventType, EventPayload>>()
    private lateinit var unsubscribe: () -> Unit

    @BeforeTest
    fun setUp() {
        sentMessages.clear()
        closedImmediate = null
        heightChanged = null
        fullHeightForced = null
        readyCalled = false
        testEventCallbacks.clear()

        unsubscribe = Encatch.on { type, payload -> testEventCallbacks.add(type to payload) }

        bridge = FormWebViewBridge(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            onClose = { immediate -> closedImmediate = immediate },
            onHeightChange = { h -> heightChanged = h },
            onForceFullHeight = { f -> fullHeightForced = f },
            onReady = { readyCalled = true },
            sendToWebView = { message -> sentMessages.add(message) },
            redirectOpener = RedirectOpener { },
            openExternal = { },
        )
    }

    @AfterTest
    fun tearDown() {
        unsubscribe()
    }

    @Test
    fun handleMessage_resize_invokesOnHeightChange_forPositiveHeight() {
        bridge.handleMessage("""{"type":"form:resize","formId":"f1","data":{"height":420}}""")
        assertEquals(420, heightChanged)
    }

    @Test
    fun handleMessage_resize_ignoresNonPositiveHeight() {
        bridge.handleMessage("""{"type":"form:resize","formId":"f1","data":{"height":0}}""")
        assertEquals(null, heightChanged)
    }

    @Test
    fun handleMessage_layout_invokesOnForceFullHeight() {
        bridge.handleMessage("""{"type":"form:layout","formId":"f1","data":{"fullHeight":true}}""")
        assertEquals(true, fullHeightForced)
    }

    @Test
    fun handleMessage_close_emitsEventAndCallsOnClose() {
        bridge.handleMessage("""{"type":"form:close","formId":"f1"}""")
        assertEquals(false, closedImmediate)
        assertTrue(testEventCallbacks.any { it.first == EventType.FORM_CLOSE })
    }

    @Test
    fun handleMessage_remindMeLater_emitsEventAndCloses() {
        bridge.handleMessage("""{"type":"form:remindmelater","formId":"f1"}""")
        assertEquals(false, closedImmediate)
        assertTrue(testEventCallbacks.any { it.first == EventType.FORM_REMIND_ME_LATER })
    }

    @Test
    fun handleMessage_malformedJson_isIgnoredWithoutThrowing() {
        bridge.handleMessage("not json at all")
        assertEquals(null, closedImmediate)
        assertTrue(sentMessages.isEmpty())
    }

    @Test
    fun handleFormReady_sendsFormConfigResetDataAndTheme() {
        val payload = ShowFormPayload(
            formId = "f1",
            formConfig = ShowFormResponse(feedbackConfigurationId = "cfg-1"),
            resetMode = ResetMode.ALWAYS,
            triggerType = TriggerType.MANUAL,
            theme = Theme.DARK,
            locale = "en-US",
            presentation = "modal",
        )
        bridge.setFormPayload(payload)
        bridge.handleFormReady()

        assertTrue(readyCalled)
        val types = sentMessages.map { it.type }
        assertTrue(types.contains(SDKMessageType.FORM_CONFIG))
        assertTrue(types.contains(SDKMessageType.RESET_DATA))
        assertTrue(types.contains(SDKMessageType.THEME))
        assertTrue(types.contains(SDKMessageType.LOCALE))
    }

    @Test
    fun handleFormReady_isIdempotent() {
        val payload = ShowFormPayload(
            formId = "f1",
            formConfig = ShowFormResponse(feedbackConfigurationId = "cfg-1"),
            resetMode = ResetMode.NEVER,
            triggerType = TriggerType.MANUAL,
        )
        bridge.setFormPayload(payload)
        bridge.handleFormReady()
        val countAfterFirst = sentMessages.size
        bridge.handleFormReady()
        assertEquals(countAfterFirst, sentMessages.size)
    }

    @Test
    fun shouldAllowNavigation_sameOriginAndPath_isAllowed() {
        val formUrl = "https://form.encatch.com/s/react-native-sdk-form?formId=f1"
        assertTrue(bridge.shouldAllowNavigation(formUrl, formUrl))
    }

    @Test
    fun shouldAllowNavigation_differentHost_isBlocked() {
        val formUrl = "https://form.encatch.com/s/react-native-sdk-form?formId=f1"
        assertFalse(bridge.shouldAllowNavigation("https://evil.example.com/phish", formUrl))
    }

    @Test
    fun shouldAllowNavigation_dataAndBlobUrls_alwaysAllowed() {
        val formUrl = "https://form.encatch.com/s/react-native-sdk-form?formId=f1"
        assertTrue(bridge.shouldAllowNavigation("data:text/html,hi", formUrl))
        assertTrue(bridge.shouldAllowNavigation("blob:abc", formUrl))
        assertTrue(bridge.shouldAllowNavigation("about:blank", formUrl))
    }

    @Test
    fun shouldAllowNavigation_nonTopFrame_alwaysAllowed() {
        val formUrl = "https://form.encatch.com/s/react-native-sdk-form?formId=f1"
        assertTrue(bridge.shouldAllowNavigation("https://evil.example.com", formUrl, isTopFrame = false))
    }

    // extractBase64Payload — regression tests for signature upload mode, whose fileData arrives
    // as a FileReader data URL (draw mode sends bare base64, which must pass through unchanged).

    @Test
    fun extractBase64Payload_bareBase64_passesThroughUnchanged() {
        assertEquals("aGVsbG8=", extractBase64Payload("aGVsbG8="))
    }

    @Test
    fun extractBase64Payload_dataUrl_stripsHeader() {
        assertEquals("aGVsbG8=", extractBase64Payload("data:image/jpeg;base64,aGVsbG8="))
        assertEquals("aGVsbG8=", extractBase64Payload("DATA:IMAGE/PNG;BASE64,aGVsbG8="))
    }

    @Test
    fun extractBase64Payload_whitespace_isRemoved() {
        assertEquals("aGVsbG8=", extractBase64Payload("  aGVs\nbG8= "))
        assertEquals("aGVsbG8=", extractBase64Payload("data:image/png;base64,aGVs\r\nbG8="))
    }

    @Test
    fun extractBase64Payload_commaWithoutDataHeader_isKept() {
        // A comma alone must not trigger stripping — only a real data-URL header does.
        assertEquals("abc,def", extractBase64Payload("abc,def"))
    }
}
