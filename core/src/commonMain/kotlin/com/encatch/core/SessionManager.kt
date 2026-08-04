package com.encatch.core

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the 30s ping loop, mirroring the `_startPingInterval`/`_scheduleNextPing`/
 * `_handleResponseMeta` trio in `encatch.ts`. Ping is suppressed while a form is visible
 * and can be rescheduled/stopped based on server-driven `pingAgainIn`/`pingOnNextPageVisit`.
 *
 * All [pingJob] bookkeeping is confined to a single-threaded control dispatcher: a bare
 * `cancel-then-assign` lets two concurrent callers (e.g. simultaneous API responses both
 * delivering `pingAgainIn`) cancel the same stale job and each install their own — one
 * overwrites the other in the field but BOTH keep running, leaking an extra 30s ping loop
 * per race (observed live on iOS as double/triple pings landing in the same second).
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionManager(
    private val scope: CoroutineScope,
    private val isFormVisible: () -> Boolean,
    private val onPing: suspend () -> Unit,
) {
    companion object {
        const val PING_INTERVAL_MS = 30_000L
    }

    private val control = Dispatchers.Default.limitedParallelism(1)

    private var pingJob: Job? = null

    @Volatile
    var isPingActive: Boolean = false
        private set

    fun startPingInterval() {
        scope.launch(control) { installPingLoop() }
    }

    /** Runs on [control] only. */
    private fun CoroutineScope.installPingLoop() {
        pingJob?.cancel()
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
        scope.launch(control) {
            isPingActive = false
            pingJob?.cancel()
            pingJob = null
        }
    }

    /**
     * Cancels the ping loop and actually waits for its in-flight iteration (if any) to finish,
     * unlike [stopPingInterval] which only requests cancellation. Callers that are about to
     * reassign shared mutable state the ping loop's `onPing` touches (see `Encatch.init`'s
     * reconfigure path) need this to close the race window where the old loop's already-running
     * `onPing()` call keeps executing after cancellation.
     */
    suspend fun stopPingIntervalAndJoin() {
        val job = withContext(control) {
            isPingActive = false
            val current = pingJob
            pingJob = null
            current
        }
        job?.cancelAndJoin()
    }

    fun scheduleNextPing(delayMs: Long) {
        scope.launch(control) {
            pingJob?.cancel()
            isPingActive = true
            pingJob = scope.launch {
                delay(delayMs)
                if (!isFormVisible()) {
                    runCatching { onPing() }
                }
                // A superseded job is cancelled by the control-confined swap — never let it
                // resurrect a second interval loop on its way out.
                if (isActive) startPingInterval()
            }
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
