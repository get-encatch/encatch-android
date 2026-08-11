package com.encatch.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Custom form-validation i18n: `projectI18nFileUrl` arrives at the TOP LEVEL of the show-form
 * response (never inside formConfiguration/questionnaireFields/appearanceProperties/
 * otherConfigurationProperties) and must be preserved and forwarded at the top level of the
 * form-config message sent to the WebView — or omitted entirely when null/missing, in which
 * case the WebView form engine falls back to its default language packs. Native code does no
 * translation loading itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShowFormI18nTest {

    private fun parse(json: String): ShowFormResponse =
        EncatchJson.parseToJsonElement(json).jsonObject.toShowFormResponse()

    // ------------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------------

    @Test
    fun parse_readsProjectI18nFileUrl_fromTopLevel() {
        val response = parse(
            """{"feedbackConfigurationId":"cfg-1","projectI18nFileUrl":"https://cdn.example.com/form-i18n/v12.json"}""",
        )
        assertEquals("https://cdn.example.com/form-i18n/v12.json", response.projectI18nFileUrl)
    }

    @Test
    fun parse_missingProjectI18nFileUrl_isNull() {
        val response = parse("""{"feedbackConfigurationId":"cfg-1"}""")
        assertNull(response.projectI18nFileUrl)
    }

    @Test
    fun parse_explicitNullProjectI18nFileUrl_isNull() {
        val response = parse("""{"feedbackConfigurationId":"cfg-1","projectI18nFileUrl":null}""")
        assertNull(response.projectI18nFileUrl)
    }

    @Test
    fun parse_doesNotReadProjectI18nFileUrl_fromNestedConfigObjects() {
        val response = parse(
            """
            {
              "feedbackConfigurationId": "cfg-1",
              "formConfiguration": {"projectI18nFileUrl": "https://cdn.example.com/wrong-1.json"},
              "questionnaireFields": {"projectI18nFileUrl": "https://cdn.example.com/wrong-2.json"},
              "appearanceProperties": {"projectI18nFileUrl": "https://cdn.example.com/wrong-3.json"},
              "otherConfigurationProperties": {"projectI18nFileUrl": "https://cdn.example.com/wrong-4.json"}
            }
            """.trimIndent(),
        )
        assertNull(response.projectI18nFileUrl)
    }

    // ------------------------------------------------------------------------
    // Forwarding to the WebView (FORM_CONFIG message)
    // ------------------------------------------------------------------------

    private val sentMessages = mutableListOf<SDKMessage>()
    private lateinit var bridge: FormWebViewBridge

    @BeforeTest
    fun setUp() {
        sentMessages.clear()
        bridge = FormWebViewBridge(
            scope = CoroutineScope(UnconfinedTestDispatcher()),
            onClose = { },
            onHeightChange = { },
            onForceFullHeight = { },
            onReady = { },
            sendToWebView = { message -> sentMessages.add(message) },
            redirectOpener = RedirectOpener { },
            openExternal = { },
        )
    }

    @AfterTest
    fun tearDown() {
        bridge.setFormPayload(null)
    }

    private fun formConfigMessageData(formConfig: ShowFormResponse): kotlinx.serialization.json.JsonObject {
        bridge.setFormPayload(
            ShowFormPayload(
                formId = "f1",
                formConfig = formConfig,
                resetMode = ResetMode.ALWAYS,
                triggerType = TriggerType.MANUAL,
                presentation = "modal",
            ),
        )
        bridge.handleFormReady()
        val message = sentMessages.first { it.type == SDKMessageType.FORM_CONFIG }
        return EncatchJson.parseToJsonElement(message.dataJson!!).jsonObject
    }

    @Test
    fun formConfigMessage_forwardsProjectI18nFileUrl_atTopLevel() {
        val data = formConfigMessageData(
            ShowFormResponse(
                feedbackConfigurationId = "cfg-1",
                projectI18nFileUrl = "https://cdn.example.com/form-i18n/v12.json",
            ),
        )
        assertEquals(
            "https://cdn.example.com/form-i18n/v12.json",
            data["projectI18nFileUrl"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun formConfigMessage_omitsProjectI18nFileUrl_whenNull() {
        val data = formConfigMessageData(ShowFormResponse(feedbackConfigurationId = "cfg-1"))
        assertFalse("projectI18nFileUrl" in data)
        assertTrue("feedbackConfigurationId" in data)
    }
}
