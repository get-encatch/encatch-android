package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/**
 * Retry queue for identifyUser / trackEvent / trackScreen, mirroring RN 1.4.4 `retry-queue.ts`
 * (item 1):
 *
 * - One flush at a time: a flush while one runs waits for it and makes it go through the queue
 *   once more, so a request is never sent twice while in flight or after it succeeded.
 * - Requests go out one at a time, in the order queued.
 * - A 4xx drops that item and the flush carries on with the next.
 * - A 429 drops that item (never retried) and holds the rest while the tracking bucket is paused
 *   (see [RateLimiter]); they go out once the pause ends.
 * - 5xx and network failures (a request times out after 30 s) are retried with backoff (2 s, then
 *   4 s): at most [MAX_RETRIES] attempts in all. A backed-off item waits without holding up the
 *   items behind it.
 *
 * Labels are persisted for debugging only; queued calls are not replayed after a restart (the
 * closure can't be rebuilt), as in the RN SDK.
 */
internal class RetryQueue(
    private val scope: CoroutineScope,
    private val storage: EncatchStorage,
    private val rateLimiter: RateLimiter? = null,
    private val logger: EncatchLogger? = null,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    companion object {
        const val MAX_RETRIES = 3
        const val BASE_BACKOFF_MS = 1000L
    }

    @Serializable
    private data class SerializableQueueItem(
        val id: String,
        val retries: Int,
        val maxRetries: Int,
        val createdAt: Long,
        val label: String,
    )

    private class QueuedRequest(
        val id: String,
        val fn: suspend () -> Unit,
        var retries: Int,
        val maxRetries: Int,
        val createdAt: Long,
        val label: String,
        /** Not sent before this time (backoff after a failure). */
        var nextAttemptAt: Long,
        /** Called when the item is dropped without succeeding (a 4xx, a 429, out of attempts, removed). */
        val onDrop: (() -> Unit)? = null,
    ) {
        /** Removed by [removeQueued] while being sent: if this attempt fails, it is dropped, not retried. */
        var removedInFlight = false
    }

    private val mutex = Mutex()
    private val queue = mutableListOf<QueuedRequest>()
    private var nextId = 0L
    /** The item being sent right now. */
    private var inFlight: QueuedRequest? = null
    /** The flush in progress, and whether a flush asked for during it wants one more pass. */
    private var running: Job? = null
    private var runAgain = false
    /** Flushes when the earliest backed-off item is due. */
    private var wakeJob: Job? = null

    init {
        rateLimiter?.onPauseEnd { scope.launch { flush() } }
    }

    private fun backoffMs(retries: Int): Long = BASE_BACKOFF_MS * (1L shl retries)

    private fun persistQueue() {
        val serializable = queue.map {
            SerializableQueueItem(it.id, it.retries, it.maxRetries, it.createdAt, it.label)
        }
        storage.setRetryQueueRaw(Json.encodeToString(serializable))
    }

    private fun isPaused(): Boolean = rateLimiter?.isPaused(RateBucket.TRACKING) == true

    /** Queues [fn] and starts a flush. */
    suspend fun enqueue(label: String, maxRetries: Int = MAX_RETRIES, onDrop: (() -> Unit)? = null, fn: suspend () -> Unit) {
        mutex.withLock {
            val now = clock()
            queue.add(QueuedRequest("$now-${nextId++}", fn, 0, maxRetries, now, label, now, onDrop))
            persistQueue()
        }
        scope.launch { flush() }
    }

    /** Sends every queued request that is due, one at a time; joins the running flush if there is one. */
    suspend fun flush() {
        val job = mutex.withLock {
            val current = running
            if (current != null && current.isActive) {
                runAgain = true
                current
            } else {
                scope.launch { runLoop() }.also { running = it }
            }
        }
        job.join()
    }

    private suspend fun runLoop() {
        val self = coroutineContext.job
        try {
            while (true) {
                mutex.withLock { runAgain = false }
                runOnce()
                val again = mutex.withLock {
                    if (runAgain) {
                        true
                    } else {
                        if (running === self) running = null
                        false
                    }
                }
                if (!again) break
            }
        } finally {
            withContext(NonCancellable) {
                mutex.withLock { if (running === self) running = null }
                scheduleWake()
            }
        }
    }

    /** Sends every due item, in order, once. */
    private suspend fun runOnce() {
        val snapshot = mutex.withLock { queue.toList() }
        for (item in snapshot) {
            // paused after a 429: hold the rest until the pause ends (the limiter flushes then)
            if (isPaused()) return
            val due = mutex.withLock {
                (queue.any { it === item } && item.nextAttemptAt <= clock()).also { if (it) inFlight = item }
            }
            if (!due) continue
            try {
                item.fn()
                mutex.withLock {
                    inFlight = null
                    queue.remove(item)
                    persistQueue()
                }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    val removed = mutex.withLock {
                        if (inFlight === item) inFlight = null
                        item.removedInFlight
                    }
                    // removed while it was being sent, and it never finished: report it now
                    if (removed) runCatching { item.onDrop?.invoke() }
                }
                throw cancel
            } catch (err: Throwable) {
                withContext(NonCancellable) { mutex.withLock { inFlight = null } }
                val status = (err as? EncatchApiException)?.status
                when {
                    item.removedInFlight -> {
                        // removed while it was being sent, then failed: not retried
                        drop(item)
                        logger?.debug("Retry queue: dropping \"${item.label}\" (removed while in flight, then failed)")
                        // a 429 still holds the rest until the pause ends
                        if (status == 429) return
                    }
                    status == 429 -> {
                        drop(item)
                        logger?.warn("[Encatch] Retry queue: dropping \"${item.label}\" (rate limited, not retried)")
                        return
                    }
                    status != null && status in 400..499 -> {
                        drop(item)
                        logger?.debug("Retry queue: dropping \"${item.label}\" (client error $status, no retry)")
                    }
                    else -> {
                        item.retries += 1
                        if (item.retries >= item.maxRetries) {
                            drop(item)
                            logger?.warn("[Encatch] Retry queue: dropping \"${item.label}\" after ${item.maxRetries} attempts")
                        } else {
                            mutex.withLock {
                                item.nextAttemptAt = clock() + backoffMs(item.retries)
                                persistQueue()
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun drop(item: QueuedRequest) {
        mutex.withLock {
            queue.remove(item)
            persistQueue()
        }
        runCatching { item.onDrop?.invoke() }
    }

    /** Arranges a flush for when the earliest backed-off item is due (while paused, the pause end flushes). */
    private suspend fun scheduleWake() {
        mutex.withLock {
            wakeJob?.cancel()
            wakeJob = null
            if (queue.isEmpty() || isPaused()) return
            val wait = max(0L, queue.minOf { it.nextAttemptAt } - clock())
            wakeJob = scope.launch {
                delay(wait)
                flush()
            }
        }
    }

    /**
     * Removes queued items with this label; returns how many. One being sent right now is not
     * reported as dropped: it may still succeed. If it fails, it is dropped (and reported) then,
     * never retried.
     */
    suspend fun removeQueued(label: String): Int {
        val removed = mutex.withLock {
            val matching = queue.filter { it.label == label }
            queue.removeAll(matching)
            if (matching.isNotEmpty()) persistQueue()
            matching.filter { item ->
                if (item === inFlight) {
                    item.removedInFlight = true
                    false
                } else {
                    true
                }
            }
        }
        removed.forEach { runCatching { it.onDrop?.invoke() } }
        return removed.size
    }

    /**
     * Forgets every queued item and the pending wake, without sending or reporting them (re-init with
     * another key: nothing queued for the old one may go out with the new key or base URL).
     */
    suspend fun clear() {
        mutex.withLock {
            wakeJob?.cancel()
            wakeJob = null
            inFlight?.removedInFlight = true
            queue.clear()
            persistQueue()
        }
    }

    fun queueSize(): Int = queue.size
}
