package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Offline retry queue for failed, idempotent API calls (identifyUser/trackEvent/trackScreen),
 * mirroring `retry-queue.ts`. Persists metadata only — the closure itself is not
 * reconstructable across process death, matching the RN SDK's own limitation.
 */
internal class RetryQueue(
    private val scope: CoroutineScope,
    private val storage: EncatchStorage,
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
    )

    private val mutex = Mutex()
    private val queue = mutableListOf<QueuedRequest>()
    // Items currently executing. `fn()` necessarily runs outside the mutex, so two overlapping
    // flush() calls (enqueue schedules one, callers often schedule another) both see a
    // not-yet-removed item and would run its request twice without this guard — observed live
    // on iOS as every trackScreen/trackEvent landing on the API twice.
    private val inFlightIds = mutableSetOf<String>()
    private var nextId = 0L
    private val pendingRetryJobs = mutableMapOf<String, Job>()

    /** True for a status-based failure whose message embeds "status <code>" in the 4xx range. */
    private fun isClientError(error: Throwable): Boolean {
        val status = (error as? EncatchApiException)?.status ?: return false
        return status in 400..499
    }

    private fun backoffMs(retries: Int): Long = BASE_BACKOFF_MS * (1L shl retries)

    private suspend fun persistQueue() {
        val serializable = queue.map {
            SerializableQueueItem(it.id, it.retries, it.maxRetries, it.createdAt, it.label)
        }
        storage.setRetryQueueRaw(Json.encodeToString(serializable))
    }

    fun enqueue(label: String, maxRetries: Int = MAX_RETRIES, fn: suspend () -> Unit) {
        scope.launch {
            mutex.withLock {
                val item = QueuedRequest(
                    id = "${currentTimeMillis()}-${nextId++}",
                    fn = fn,
                    retries = 0,
                    maxRetries = maxRetries,
                    createdAt = currentTimeMillis(),
                    label = label,
                )
                queue.add(item)
                persistQueue()
            }
            flush()
        }
    }

    suspend fun flush() {
        val snapshot = mutex.withLock { queue.toList() }
        for (item in snapshot) {
            attempt(item)
        }
    }

    private suspend fun attempt(item: QueuedRequest) {
        val shouldRun = mutex.withLock {
            if (queue.none { it.id == item.id } || item.id in inFlightIds) {
                false
            } else {
                inFlightIds.add(item.id)
                true
            }
        }
        if (!shouldRun) return
        try {
            attemptLocked(item)
        } finally {
            mutex.withLock { inFlightIds.remove(item.id) }
        }
    }

    private suspend fun attemptLocked(item: QueuedRequest) {
        try {
            item.fn()
            mutex.withLock {
                queue.removeAll { it.id == item.id }
                persistQueue()
            }
        } catch (err: Throwable) {
            if (isClientError(err)) {
                mutex.withLock {
                    queue.removeAll { it.id == item.id }
                    persistQueue()
                }
                return
            }
            item.retries += 1
            if (item.retries >= item.maxRetries) {
                mutex.withLock {
                    queue.removeAll { it.id == item.id }
                    persistQueue()
                }
            } else {
                mutex.withLock { persistQueue() }
                val delayMs = backoffMs(item.retries)
                pendingRetryJobs[item.id] = scope.launch {
                    delay(delayMs)
                    attempt(item)
                }
            }
        }
    }

    fun queueSize(): Int = queue.size
}
