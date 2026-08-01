package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
