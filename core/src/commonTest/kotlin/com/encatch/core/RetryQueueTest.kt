package com.encatch.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class RetryQueueTest {

    @Test
    fun enqueue_successfulCall_isRemovedFromQueue() = runTest {
        val storage = EncatchStorage(InMemorySettings())
        val queue = RetryQueue(this, storage)

        queue.enqueue("trackEvent") { /* succeeds */ }
        advanceUntilIdle()

        assertEquals(0, queue.queueSize())
    }

    @Test
    fun enqueue_clientError_isDroppedWithoutRetry() = runTest {
        val storage = EncatchStorage(InMemorySettings())
        val queue = RetryQueue(this, storage)
        var attempts = 0

        queue.enqueue("identifyUser") {
            attempts++
            throw EncatchApiException("identify-user", 404, "not found")
        }
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun enqueue_serverError_retriesUpToMaxThenDrops() = runTest {
        val storage = EncatchStorage(InMemorySettings())
        val queue = RetryQueue(this, storage)
        var attempts = 0

        queue.enqueue("trackScreen") {
            attempts++
            throw EncatchApiException("track-screen", 500, "server error")
        }
        advanceUntilIdle()

        // MAX_RETRIES (3) is the total attempt count before dropping (matches retry-queue.ts semantics).
        assertEquals(RetryQueue.MAX_RETRIES, attempts)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun enqueue_succeedsAfterTransientServerErrors() = runTest {
        val storage = EncatchStorage(InMemorySettings())
        val queue = RetryQueue(this, storage)
        var attempts = 0

        queue.enqueue("trackEvent") {
            attempts++
            if (attempts < 3) throw EncatchApiException("track-event", 503, "unavailable")
        }
        advanceUntilIdle()

        assertEquals(3, attempts)
        assertEquals(0, queue.queueSize())
    }
}
