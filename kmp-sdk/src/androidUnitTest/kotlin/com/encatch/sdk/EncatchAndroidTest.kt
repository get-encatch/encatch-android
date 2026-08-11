package com.encatch.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Verifies the Android [Encatch] actual is a trivial, correctly-wired passthrough to `:core`'s
 * `com.encatch.core.Encatch` singleton — the members exercised here don't require network access
 * (they're synchronous state reads/writes), so they can run as plain JVM unit tests without any
 * Android instrumentation, matching `:core`'s own `commonTest` conventions.
 */
class EncatchAndroidTest {

    @Test
    fun `isInitialized reflects core Encatch before init is called`() {
        assertFalse(Encatch.isInitialized)
    }

    @Test
    fun `getters read through to core Encatch defaults before init`() {
        assertNull(Encatch.apiKey)
        assertNull(Encatch.locale)
        assertNull(Encatch.deviceId)
        assertNull(Encatch.userName)
        assertFalse(Encatch.debugMode)
        assertFalse(Encatch.isFullScreen)
        assertEquals(Theme.SYSTEM, Encatch.theme)
    }

    @Test
    fun `addToResponse getPendingResponses and clearPendingResponses round-trip through core`() {
        Encatch.clearPendingResponses()
        assertEquals(emptyMap(), Encatch.getPendingResponses())

        Encatch.addToResponse("q1", "answer")
        Encatch.addToResponse("q2", 42)

        val pending = Encatch.getPendingResponses()
        assertEquals("answer", pending["q1"])
        assertEquals(42L, pending["q2"]) // core round-trips integral numbers through JsonElement as Long

        Encatch.clearPendingResponses()
        assertEquals(emptyMap(), Encatch.getPendingResponses())
    }

    @Test
    fun `on registers a callback that emitEvent invokes, and the returned unsubscribe removes it`() {
        var received: EventPayload? = null
        val unsubscribe = Encatch.on { type, payload ->
            if (type == EventType.FORM_COMPLETE) received = payload
        }

        Encatch.emitEvent(EventType.FORM_COMPLETE, EventPayload(formId = "abc"))
        assertEquals("abc", received?.formId)

        unsubscribe()
        received = null
        Encatch.emitEvent(EventType.FORM_COMPLETE, EventPayload(formId = "xyz"))
        assertNull(received)
    }

    @Test
    fun `setTheme updates core Encatch's theme getter`() {
        // Deliberately doesn't exercise setLocale/setCountry here: `:core`'s implementation
        // launches a background coroutine to persist preferences via `EncatchStorage`, which is
        // `lateinit` and only set up by `Encatch.init(...)` — calling it pre-init logs a harmless
        // but noisy uncaught-exception stack trace on the test coroutine dispatcher. `setTheme`
        // only updates in-memory state, so it's safe to call without `init`.
        Encatch.setTheme(Theme.DARK)
        assertEquals(Theme.DARK, Encatch.theme)

        // Reset so this test doesn't leak state into other tests sharing the same process-wide
        // com.encatch.core.Encatch singleton.
        Encatch.setTheme(Theme.SYSTEM)
    }
}
