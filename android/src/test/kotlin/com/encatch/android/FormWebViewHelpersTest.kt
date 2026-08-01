package com.encatch.android

import com.encatch.core.uploadMimeType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class FormWebViewHelpersTest {

    @Test
    fun buildFormWebViewUrl_includesFormIdAndInstanceKey() {
        val url = buildFormWebViewUrl("https://form.encatch.com", "form-123", 5, debugMode = false)
        assertTrue(url.startsWith("https://form.encatch.com/s/react-native-sdk-form?"))
        assertTrue(url.contains("formId=form-123"))
        assertTrue(url.contains("ts=5"))
        assertTrue(!url.contains("debug="))
        assertTrue(!url.contains("presentation="))
    }

    @Test
    fun buildFormWebViewUrl_addsDebugAndPresentationWhenSet() {
        val url = buildFormWebViewUrl("https://form.encatch.com", "form-1", 1, debugMode = true, presentation = "inline")
        assertTrue(url.contains("debug=true"))
        assertTrue(url.contains("presentation=inline"))
    }

    @Test
    fun uploadMimeType_stripsCharsetSuffix() {
        assertEquals("image/png", uploadMimeType("image/png; charset=binary"))
    }

    @Test
    fun uploadMimeType_defaultsWhenBlankOrNull() {
        assertEquals("application/octet-stream", uploadMimeType(null))
        assertEquals("application/octet-stream", uploadMimeType(""))
        assertEquals("application/octet-stream", uploadMimeType("   "))
    }
}
