package com.encatch.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The 429 pause (RN 1.4.4 item 2, `rate-limit.ts`, `http-error.ts`). */
@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitTest {

    @Test
    fun retry_after_in_seconds_or_an_http_date() {
        assertEquals(120_000L, parseRetryAfter("120"))
        assertEquals(1_500L, parseRetryAfter(" 1.5 "))
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("soon"))
        val at = 1_445_412_480_000L // Wed, 21 Oct 2015 07:28:00 GMT
        assertEquals(at, parseHttpDate("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(30_000L, parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now = at - 30_000))
        assertEquals(0L, parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT", now = at + 5_000))
    }

    @Test
    fun buckets_follow_the_gateway() {
        assertEquals(RateBucket.TRACKING, bucketOf(Endpoints.IDENTIFY_USER))
        assertEquals(RateBucket.TRACKING, bucketOf(Endpoints.TRACK_EVENT))
        assertEquals(RateBucket.TRACKING, bucketOf(Endpoints.TRACK_SCREEN))
        assertEquals(RateBucket.TRACKING, bucketOf(Endpoints.PING))
        assertEquals(RateBucket.SHOW_FORM, bucketOf(Endpoints.SHOW_FORM))
        assertEquals(RateBucket.SUBMIT_FORM, bucketOf(Endpoints.SUBMIT_FORM))
        assertEquals(RateBucket.OTHER, bucketOf(Endpoints.DISMISS_FORM))
    }

    @Test
    fun default_pause_cap_and_one_warning_per_pause() = runTest {
        val warnings = mutableListOf<String>()
        var ended = 0
        val limiter = RateLimiter(this, warn = { warnings.add(it) }, clock = { testScheduler.currentTime })
        limiter.onPauseEnd { ended++ }

        limiter.pauseFor(RateBucket.TRACKING, null)
        assertEquals(RateLimiter.DEFAULT_PAUSE_MS, limiter.remainingMs(RateBucket.TRACKING))
        limiter.pauseFor(RateBucket.TRACKING, 10_000) // shorter: no change, no second warning
        limiter.pauseFor(RateBucket.SHOW_FORM, 60 * 60_000L)
        assertEquals(RateLimiter.MAX_PAUSE_MS, limiter.remainingMs(RateBucket.SHOW_FORM))
        limiter.pauseFor(RateBucket.OTHER, 10_000)
        assertFalse(limiter.isPaused(RateBucket.OTHER))
        assertFalse(limiter.isPaused(RateBucket.SUBMIT_FORM))
        assertEquals(2, warnings.size, warnings.toString())

        advanceTimeBy(RateLimiter.DEFAULT_PAUSE_MS + 10)
        assertFalse(limiter.isPaused(RateBucket.TRACKING))
        assertTrue(limiter.isPaused(RateBucket.SHOW_FORM))
        assertEquals(1, ended)

        limiter.pauseFor(RateBucket.TRACKING, 1_000) // a new pause: a new warning
        assertEquals(3, warnings.size)
        limiter.clear()
        assertFalse(limiter.isPaused(RateBucket.SHOW_FORM))
        advanceUntilIdle()
    }
}
