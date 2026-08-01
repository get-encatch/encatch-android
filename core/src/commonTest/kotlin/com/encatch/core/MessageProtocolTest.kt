package com.encatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageProtocolTest {

    @Test
    fun formMessage_decodesEnvelopeAndResolvesType() {
        val json = """{"type":"form:submit","formId":"f1","data":{"feedbackConfigurationId":"cfg-1"}}"""
        val message = EncatchJson.decodeFromString(FormMessage.serializer(), json)

        assertEquals("form:submit", message.type)
        assertEquals(FormMessageType.SUBMIT, message.messageType)
        assertEquals("f1", message.formId)
        assertEquals("cfg-1", message.data?.get("feedbackConfigurationId")?.toString()?.trim('"'))
    }

    @Test
    fun formMessageType_fromWire_unknownValueReturnsNull() {
        assertNull(FormMessageType.fromWire("form:unknown-thing"))
    }

    @Test
    fun formMessageType_fromWire_matchesAllDeclaredValues() {
        val allWireValues = listOf(
            "form:ready", "form:submit", "form:complete", "form:close", "form:error",
            "form:resize", "form:layout", "form:closeButton", "form:themeData",
            "form:refineTextRequest", "form:started", "form:answered", "form:section:change",
            "form:show", "form:readyToDismiss", "form:uploadFileRequest", "form:qnaWithAiRequest",
            "form:remindmelater", "form:ctaTriggered",
        )
        allWireValues.forEach { wire ->
            assertEquals(wire, FormMessageType.fromWire(wire)?.wireValue)
        }
    }

    @Test
    fun eventType_fromWire_roundTrips() {
        EventType.entries.forEach { type ->
            assertEquals(type, EventType.fromWire(type.wireValue))
        }
    }
}
