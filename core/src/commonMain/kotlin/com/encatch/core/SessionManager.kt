package com.encatch.core

import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

/**
 * Owns the 30 s ping loop, mirroring `_startPingInterval`/`_scheduleNextPing`/`_handleResponseMeta`
 * in RN 1.4.4 `encatch.ts` (items 18, 19). No ping while a form is visible; the caller's [onPing]
 * also skips it during a 429 pause, after a secure signature expired and while stopped.
 *
 * - Every reply's `pingAgainIn` is honoured while pinging is active, including the reply of the
 *   server-scheduled ping itself: pinging stays active while it waits.
 * - After a server-scheduled ping the regular interval resumes only if pinging is still active
 *   and the reply did not schedule the next ping.
 * - The waiting (the timer) and the ping request are separate jobs: replacing or stopping the
 *   timer never cancels a ping in flight, so its reply (and a survey in it) is still read, and a
 *   stop made while it is in flight is not undone when the reply arrives.
 *
 * All timer bookkeeping is confined to a single-threaded control dispatcher, so two replies
 * delivering `pingAgainIn` at once can't leave two timers running.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SessionManager(
    private val scope: CoroutineScope,
    private val isFormVisible: () -> Boolean,
    private val onPing: suspend () -> Unit,
    private val intervalMs: Long = PING_INTERVAL_MS,
) {
    companion object {
        const val PING_INTERVAL_MS = 30_000L
    }

    private val control = Dispatchers.Default.limitedParallelism(1)

    /** The wait for the next ping: the regular loop, or a server-scheduled one. */
    private var timerJob: Job? = null
    /** The ping request in flight, if any. */
    @Volatile private var inFlightPing: Job? = null

    @Volatile
    var isPingActive: Boolean = false
        private set

    fun startPingInterval() {
        scope.launch(control) { installLoop() }
    }

    /** [startPingInterval], done before it returns. */
    suspend fun startPingIntervalNow() = withContext(control) { installLoop() }

    /** Runs on [control] only. */
    private fun installLoop() {
        timerJob?.cancel()
        isPingActive = true
        timerJob = scope.launch {
            while (true) {
                delay(intervalMs)
                firePing()
            }
        }
    }

    /** Sends a ping in its own job and waits for it; cancelling the waiting timer leaves the ping alone. */
    private suspend fun firePing() {
        if (isFormVisible()) return
        val ping = scope.launch {
            try {
                onPing()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Throwable) {
                // best effort
            }
        }
        inFlightPing = ping
        ping.join()
    }

    fun stopPingInterval() {
        scope.launch(control) { stopNow() }
    }

    /** [stopPingInterval], done before it returns. */
    suspend fun stopPingIntervalNow() = withContext(control) { stopNow() }

    /** Runs on [control] only. */
    private fun stopNow() {
        isPingActive = false
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Stops pinging and waits for a ping in flight to finish, unlike [stopPingInterval]. Callers that
     * are about to reassign shared state the ping touches (see `Encatch.init`'s reconfigure path)
     * need this so the old ping does not keep running after it.
     */
    suspend fun stopPingIntervalAndJoin() {
        val (timer, ping) = withContext(control) {
            isPingActive = false
            val t = timerJob
            timerJob = null
            t to inFlightPing
        }
        timer?.cancelAndJoin()
        ping?.cancelAndJoin()
    }

    /** The server asked for the next ping in [delayMs]; ignored while pinging is not active (stopped, paused). */
    suspend fun scheduleNextPing(delayMs: Long) = withContext(control) {
        if (isPingActive) installScheduled(delayMs)
    }

    /** Runs on [control] only. */
    private fun installScheduled(delayMs: Long) {
        timerJob?.cancel()
        isPingActive = true
        timerJob = scope.launch {
            val me = coroutineContext.job
            delay(delayMs)
            firePing()
            withContext(control) {
                // back to the regular interval, unless the reply scheduled the next ping (this timer
                // was replaced) or pinging was stopped meanwhile
                if (isPingActive && timerJob === me) installLoop()
            }
        }
    }

    /** Applies `pingAgainIn`/`pingOnNextPageVisit` from any API response, mirrors `_handleResponseMeta`. */
    suspend fun handleResponseMeta(meta: ResponseMeta) {
        if (meta.pingAgainIn != null && meta.pingAgainIn > 0) {
            scheduleNextPing((meta.pingAgainIn * 1000).toLong())
        }
        if (meta.pingOnNextPageVisit == false) {
            stopPingIntervalNow()
        }
    }
}
