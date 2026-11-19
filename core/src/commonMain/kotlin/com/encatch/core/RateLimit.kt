package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Groups of calls paused together after a 429, following how the gateway counts requests
 * (RN 1.4.4 item 2, `rate-limit.ts`): identify, track-event (survey events too), track-screen and
 * ping share one; show-form and submit-form have their own; anything else is never paused.
 */
internal enum class RateBucket { TRACKING, SHOW_FORM, SUBMIT_FORM, OTHER }

internal fun bucketOf(endpoint: String): RateBucket = when (endpoint.substringAfterLast('/')) {
    "identify-user", "track-event", "track-screen", "ping" -> RateBucket.TRACKING
    "show-form" -> RateBucket.SHOW_FORM
    "submit-form" -> RateBucket.SUBMIT_FORM
    else -> RateBucket.OTHER
}

/**
 * The 429 pause: after a 429 the bucket is paused for `Retry-After` (seconds or an HTTP date;
 * [DEFAULT_PAUSE_MS] without one, at most [MAX_PAUSE_MS]), with one warning per pause. Calls in a
 * paused bucket are skipped before queuing; what the retry queue holds goes out when it ends.
 */
internal class RateLimiter(
    private val scope: CoroutineScope,
    private val warn: (String) -> Unit,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    companion object {
        const val DEFAULT_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 5 * 60_000L
    }

    private val lock = Mutex()
    @Volatile private var pausedUntil: Map<RateBucket, Long> = emptyMap()
    private val timers = mutableMapOf<RateBucket, Job>()
    @Volatile private var listeners: List<() -> Unit> = emptyList()

    /** Starts (or extends) a pause after a 429. */
    suspend fun pauseFor(bucket: RateBucket, retryAfterMs: Long?) {
        if (bucket == RateBucket.OTHER) return
        lock.withLock {
            val ms = min(MAX_PAUSE_MS, retryAfterMs?.takeIf { it >= 0 } ?: DEFAULT_PAUSE_MS)
            val now = clock()
            val until = now + ms
            val current = pausedUntil[bucket] ?: 0L
            if (until <= current) return
            if (current <= now) {
                warn(
                    "[Encatch] Rate limited (429): pausing ${bucket.name.lowercase().replace('_', '-')} requests for " +
                        "${(ms + 999) / 1000} s. Calls made during the pause are skipped.",
                )
            }
            pausedUntil = pausedUntil + (bucket to until)
            timers[bucket]?.cancel()
            timers[bucket] = scope.launch {
                delay(ms)
                // the timer may wake a moment before the clock reaches the end of the pause
                while (remainingMs(bucket) > 0) delay(remainingMs(bucket) + 1)
                listeners.forEach { runCatching { it() } }
            }
        }
    }

    fun isPaused(bucket: RateBucket): Boolean = (pausedUntil[bucket] ?: 0L) > clock()

    /** Milliseconds until the bucket's pause ends (0 when not paused). */
    fun remainingMs(bucket: RateBucket): Long = max(0L, (pausedUntil[bucket] ?: 0L) - clock())

    /** Called whenever a pause ends (the retry queue sends what it held). */
    fun onPauseEnd(listener: () -> Unit) {
        listeners = listeners + listener
    }

    /** Clears every pause (full SDK reset). */
    suspend fun clear() {
        lock.withLock {
            pausedUntil = emptyMap()
            timers.values.forEach { it.cancel() }
            timers.clear()
        }
    }
}

/** A Retry-After header: a delay in seconds, or an HTTP date. Milliseconds from [now], or null when missing or invalid. */
internal fun parseRetryAfter(value: String?, now: Long = currentTimeMillis()): Long? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    if (Regex("""^\d+(\.\d+)?$""").matches(trimmed)) return (trimmed.toDouble() * 1000).roundToLong()
    val at = parseHttpDate(trimmed) ?: return null
    return max(0L, at - now)
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** IMF-fixdate (`Sun, 06 Nov 1994 08:49:37 GMT`), the form Retry-After uses, to epoch ms. */
internal fun parseHttpDate(value: String): Long? {
    val m = Regex("""^[A-Za-z]{3},\s*(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{2}):(\d{2}):(\d{2})\s+(GMT|UTC)$""")
        .matchEntire(value.trim()) ?: return null
    val day = m.groupValues[1].toInt()
    val month = MONTHS.indexOf(m.groupValues[2].lowercase()) + 1
    if (month == 0) return null
    val year = m.groupValues[3].toLong()
    val (h, mi, s) = Triple(m.groupValues[4].toLong(), m.groupValues[5].toLong(), m.groupValues[6].toLong())
    // days from the civil date (Howard Hinnant's algorithm)
    val y = if (month <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val mp = (month + 9) % 12
    val doy = (153 * mp + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    val days = era * 146097 + doe - 719468
    return ((days * 24 + h) * 60 + mi) * 60_000 + s * 1000
}
