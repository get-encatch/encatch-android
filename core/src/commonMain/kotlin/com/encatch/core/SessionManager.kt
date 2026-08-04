package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the 30s ping loop, mirroring the `_startPingInterval`/`_scheduleNextPing`/
 * `_handleResponseMeta` trio in `encatch.ts`. Ping is suppressed while a form is visible
 * and can be rescheduled/stopped based on server-driven `pingAgainIn`/`pingOnNextPageVisit`.
 */
internal class SessionManager(
    private val scope: CoroutineScope,
    private val isFormVisible: () -> Boolean,
    private val onPing: suspend () -> Unit,
) {
    companion object {
        const val PING_INTERVAL_MS = 30_000L
    }

    private var pingJob: Job? = null

    var isPingActive: Boolean = false
        private set

    fun startPingInterval() {
        stopPingInterval()
        isPingActive = true
        pingJob = scope.launch {
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!isFormVisible()) {
                    runCatching { onPing() }
                }
            }
        }
    }

    fun stopPingInterval() {
        isPingActive = false
        pingJob?.cancel()
        pingJob = null
    }

    /**
     * Cancels the ping loop and actually waits for its in-flight iteration (if any) to finish,
     * unlike [stopPingInterval] which only requests cancellation and returns immediately.
     * Callers that are about to reassign shared mutable state the ping loop's `onPing` touches
     * (see `Encatch.init`'s reconfigure path) need this to close the race window where the old
     * loop's already-running `onPing()` call keeps executing after cancellation.
     */
    suspend fun stopPingIntervalAndJoin() {
        isPingActive = false
        val job = pingJob
        pingJob = null
        job?.cancelAndJoin()
    }

    fun scheduleNextPing(delayMs: Long) {
        pingJob?.cancel()
        pingJob = scope.launch {
            delay(delayMs)
            if (!isFormVisible()) {
                runCatching { onPing() }
            }
            startPingInterval()
        }
    }

    /** Applies `pingAgainIn`/`pingOnNextPageVisit` from any API response, mirrors `_handleResponseMeta`. */
    fun handleResponseMeta(meta: ResponseMeta) {
        if (meta.pingAgainIn != null && meta.pingAgainIn > 0 && isPingActive) {
            scheduleNextPing((meta.pingAgainIn * 1000).toLong())
        }
        if (meta.pingOnNextPageVisit == false) {
            stopPingInterval()
        }
    }
}
