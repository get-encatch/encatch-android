package com.encatch.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The retry queue (RN 1.4.4 item 1), in virtual time: the queue's clock is the test scheduler's. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryQueueTest {

    private fun TestScope.newQueue(limiter: RateLimiter? = null) =
        RetryQueue(this, EncatchStorage(InMemorySettings()), limiter, clock = { testScheduler.currentTime })

    @Test
    fun enqueue_successfulCall_isRemovedFromQueue() = runTest {
        val queue = newQueue()

        queue.enqueue("trackEvent") { /* succeeds */ }
        advanceUntilIdle()

        assertEquals(0, queue.queueSize())
    }

    @Test
    fun enqueue_clientError_isDroppedWithoutRetry() = runTest {
        val queue = newQueue()
        var attempts = 0
        var dropped = 0

        queue.enqueue("identifyUser", onDrop = { dropped++ }) {
            attempts++
            throw EncatchApiException("identify-user", 404, "not found")
        }
        advanceUntilIdle()

        assertEquals(1, attempts)
        assertEquals(1, dropped)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun enqueue_serverError_retriesUpToMaxThenDrops() = runTest {
        val queue = newQueue()
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
        val queue = newQueue()
        var attempts = 0

        queue.enqueue("trackEvent") {
            attempts++
            if (attempts < 3) throw EncatchApiException("track-event", 503, "unavailable")
        }
        advanceUntilIdle()

        assertEquals(3, attempts)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun retries_back_off_two_then_four_seconds() = runTest {
        val queue = newQueue()
        val at = mutableListOf<Long>()

        queue.enqueue("trackEvent") {
            at.add(testScheduler.currentTime)
            throw EncatchApiException("track-event", 502, "bad gateway")
        }
        advanceUntilIdle()

        assertEquals(listOf(0L, 2_000L, 6_000L), at)
    }

    @Test
    fun overlapping_flushes_send_an_item_once() = runTest {
        val queue = newQueue()
        var sends = 0

        queue.enqueue("trackEvent") {
            sends++
            delay(1_000)
        }
        repeat(3) { launch { queue.flush() } }
        advanceUntilIdle()

        assertEquals(1, sends)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun a_4xx_drops_that_item_and_the_flush_carries_on() = runTest {
        val queue = newQueue()
        val sent = mutableListOf<String>()

        queue.enqueue("a") { sent.add("a"); throw EncatchApiException("track-event", 400, "bad") }
        queue.enqueue("b") { sent.add("b") }
        queue.enqueue("c") { sent.add("c") }
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), sent)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun a_backed_off_item_does_not_hold_up_the_items_behind_it() = runTest {
        val queue = newQueue()
        val sent = mutableListOf<Pair<String, Long>>()
        var failures = 0

        queue.enqueue("flaky") {
            sent.add("flaky" to testScheduler.currentTime)
            if (failures++ == 0) throw EncatchApiException("track-event", 500, "oops")
        }
        queue.enqueue("next") { sent.add("next" to testScheduler.currentTime) }
        advanceUntilIdle()

        assertEquals(listOf("flaky" to 0L, "next" to 0L, "flaky" to 2_000L), sent)
    }

    @Test
    fun a_429_drops_the_item_and_holds_the_rest_until_the_pause_ends() = runTest {
        val limiter = RateLimiter(this, warn = {}, clock = { testScheduler.currentTime })
        val queue = newQueue(limiter)
        var limitedAttempts = 0
        var heldSentAt = -1L
        var dropped = 0

        queue.enqueue("limited", onDrop = { dropped++ }) {
            limitedAttempts++
            limiter.pauseFor(RateBucket.TRACKING, 5_000) // what the API client does on a 429
            throw EncatchApiException("track-event", 429, "too many")
        }
        queue.enqueue("held") { heldSentAt = testScheduler.currentTime }
        advanceUntilIdle()

        assertEquals(1, limitedAttempts, "a 429 is never retried")
        assertEquals(1, dropped)
        assertTrue(heldSentAt >= 5_000, "held until the pause ended, sent at $heldSentAt")
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun remove_queued_drops_items_with_that_label() = runTest {
        val limiter = RateLimiter(this, warn = {}, clock = { testScheduler.currentTime })
        limiter.pauseFor(RateBucket.TRACKING, 1_000)
        val queue = newQueue(limiter)
        val sent = mutableListOf<String>()
        queue.enqueue("identifyUser") { sent.add("first") }
        queue.enqueue("trackEvent") { sent.add("event") }
        assertEquals(1, queue.removeQueued("identifyUser"))
        queue.enqueue("identifyUser") { sent.add("latest") }
        advanceUntilIdle()

        assertEquals(listOf("event", "latest"), sent)
    }

    @Test
    fun removing_leaves_the_item_in_flight_to_finish_and_reports_it_only_if_it_fails() = runTest {
        val queue = newQueue()
        val dropped = mutableListOf<String>()
        var attempts = 0
        queue.enqueue("identifyUser", onDrop = { dropped += "in-flight" }) {
            attempts++
            delay(1_000)
            throw EncatchApiException("identify-user", 503, "unavailable")
        }
        queue.enqueue("identifyUser", onDrop = { dropped += "queued" }) { }
        advanceTimeBy(500) // the first is being sent
        assertEquals(1, queue.removeQueued("identifyUser"), "only the one not yet sent counts")
        assertEquals(listOf("queued"), dropped)
        advanceUntilIdle()
        assertEquals(1, attempts, "a removed item that fails is not retried")
        assertEquals(listOf("queued", "in-flight"), dropped)
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun an_item_in_flight_when_the_queue_is_cleared_is_not_retried_if_it_fails() = runTest {
        val queue = newQueue()
        var attempts = 0
        var dropped = 0
        queue.enqueue("identifyUser", onDrop = { dropped++ }) {
            attempts++
            delay(1_000)
            throw EncatchApiException("identify-user", 503, "unavailable")
        }
        advanceTimeBy(500)
        queue.clear()
        advanceUntilIdle()
        assertEquals(1, attempts, "not retried")
        assertEquals(1, dropped, "reported once it failed")
        assertEquals(0, queue.queueSize())
    }

    @Test
    fun an_item_removed_in_flight_that_succeeds_is_never_reported_dropped() = runTest {
        val queue = newQueue()
        var dropped = 0
        queue.enqueue("identifyUser", onDrop = { dropped++ }) { delay(1_000) }
        advanceTimeBy(500)
        assertEquals(0, queue.removeQueued("identifyUser"))
        advanceUntilIdle()
        assertEquals(0, dropped)
        assertEquals(0, queue.queueSize())
    }
}
