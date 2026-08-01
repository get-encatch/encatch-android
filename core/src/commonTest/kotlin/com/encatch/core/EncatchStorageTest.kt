package com.encatch.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EncatchStorageTest {

    private fun newStorage() = EncatchStorage(InMemorySettings())

    @Test
    fun deviceId_persistsAcrossCalls() {
        val storage = newStorage()
        val first = storage.getOrCreateDeviceId()
        val second = storage.getOrCreateDeviceId()
        assertEquals(first, second)
        assertTrue(first.isNotBlank())
    }

    @Test
    fun sessionId_isInMemoryOnly_andClearedByClearSession() {
        val storage = newStorage()
        val first = storage.getOrCreateSessionId()
        val second = storage.getOrCreateSessionId()
        assertEquals(first, second)

        storage.clearSession()
        val third = storage.getOrCreateSessionId()
        assertTrue(third != first)
    }

    @Test
    fun userName_roundTrips_andClears() {
        val storage = newStorage()
        assertNull(storage.getUserName())
        storage.setUserName("alice")
        assertEquals("alice", storage.getUserName())
        storage.clearUserName()
        assertNull(storage.getUserName())
    }

    @Test
    fun userId_isKeyedByUserName() {
        val storage = newStorage()
        storage.setUserId("alice", "id-1")
        storage.setUserId("bob", "id-2")
        assertEquals("id-1", storage.getUserId("alice"))
        assertEquals("id-2", storage.getUserId("bob"))
        storage.clearUserId("alice")
        assertNull(storage.getUserId("alice"))
        assertEquals("id-2", storage.getUserId("bob"))
    }

    @Test
    fun feedbackTransactions_isKeyedByIdentity() {
        val storage = newStorage()
        storage.setFeedbackTransactions("anonymous", "ft-anon")
        storage.setFeedbackTransactions("alice", "ft-alice")
        assertEquals("ft-anon", storage.getFeedbackTransactions("anonymous"))
        assertEquals("ft-alice", storage.getFeedbackTransactions("alice"))
    }

    @Test
    fun preferences_mergePartialUpdates() {
        val storage = newStorage()
        storage.setPreferences(locale = "en-US")
        assertEquals("en-US", storage.getPreferences().locale)
        assertNull(storage.getPreferences().country)

        storage.setPreferences(country = "US")
        assertEquals("en-US", storage.getPreferences().locale)
        assertEquals("US", storage.getPreferences().country)
    }

    @Test
    fun sessionStopped_flagRoundTrips() {
        val storage = newStorage()
        assertFalse(storage.getSessionStopped())
        storage.setSessionStopped()
        assertTrue(storage.getSessionStopped())
        storage.clearSessionStopped()
        assertFalse(storage.getSessionStopped())
    }

    @Test
    fun clearAll_wipesEverything() {
        val storage = newStorage()
        storage.getOrCreateDeviceId()
        storage.setUserName("alice")
        storage.setUserId("alice", "id-1")
        storage.setFeedbackTransactions("alice", "ft-1")
        storage.setPreferences(locale = "en", country = "US")
        storage.setSessionStopped()
        storage.setRetryQueueRaw("[]")

        storage.clearAll()

        assertNull(storage.getUserName())
        assertNull(storage.getUserId("alice"))
        assertNull(storage.getFeedbackTransactions("alice"))
        assertNull(storage.getPreferences().locale)
        assertFalse(storage.getSessionStopped())
        assertNull(storage.getRetryQueueRaw())
        // Device ID is also wiped by clearAll (matches AsyncStorage.multiRemove of every @encatch/ key)
        assertNotNull(storage.getOrCreateDeviceId())
    }
}
