import Foundation

/// Offline retry queue for failed, idempotent API calls (identifyUser/trackEvent/trackScreen),
/// mirroring `retry-queue.ts` of RN 1.4.4 (item 1). Persists metadata only — the closure itself is
/// not reconstructable across process death, matching the RN SDK's own limitation.
///
/// - One flush at a time: `flush()` during a flush joins it and makes it go through the queue once
///   more, so a request is never sent twice while in flight or after it succeeded.
/// - Requests go out one at a time, in the order they were queued.
/// - A 4xx drops that item and the flush carries on with the next.
/// - A 429 drops the item (never retried) and pauses the tracking bucket for Retry-After; items
///   behind it are held, then sent once the pause ends.
/// - 5xx and network failures: backoff 2 s, then 4 s — at most 3 attempts, then dropped. A
///   backed-off item waits without holding up the items behind it.
///
/// Ported as a Swift `actor` rather than a class guarded by a Kotlin `Mutex` — the idiomatic Swift
/// equivalent for the same "all queue mutation is serialized" guarantee.
actor RetryQueue {
    static let maxRetries = 3
    static let baseBackoffMs: Int64 = 1000
    /// Everything in this queue (identify, track-event, track-screen) counts against this bucket.
    static let bucket = RateLimitBucket.tracking

    private struct SerializableQueueItem: Codable {
        let id: String
        let retries: Int
        let maxRetries: Int
        let createdAt: Int64
        let label: String
    }

    private final class QueuedRequest {
        let id: String
        let fn: () async throws -> Void
        var retries: Int
        let maxRetries: Int
        let createdAt: Int64
        let label: String
        /// Not sent before this time (monotonic ms; backoff after a failure).
        var nextAttemptAtMs: Int64 = 0
        /// Called when the item is dropped without succeeding.
        let onDrop: (@Sendable () -> Void)?

        init(
            id: String,
            fn: @escaping () async throws -> Void,
            retries: Int,
            maxRetries: Int,
            createdAt: Int64,
            label: String,
            onDrop: (@Sendable () -> Void)?
        ) {
            self.id = id
            self.fn = fn
            self.retries = retries
            self.maxRetries = maxRetries
            self.createdAt = createdAt
            self.label = label
            self.onDrop = onDrop
        }
    }

    private let storage: EncatchStorage
    private var queue: [QueuedRequest] = []
    private var nextId: Int64 = 0
    /// Items currently executing. Actor methods suspend at every `await`, so two overlapping
    /// `flush()` calls (enqueue schedules one, callers often schedule another) both see a
    /// not-yet-removed item and would run its request twice without this guard — observed live
    /// as every trackScreen/trackEvent landing on the API twice.
    private var inFlightIds: Set<String> = []
    /// In-flight items removed by [removeQueued] or [clear]: if that attempt fails, they are dropped
    /// (and reported) instead of retried.
    private var removedInFlightIds: Set<String> = []
    /// The flush in progress, if any, and whether it must go through the queue once more.
    private var running: Task<Void, Never>?
    private var runAgain = false
    /// Flushes when the earliest backed-off item is due.
    private var wakeTask: Task<Void, Never>?
    private let rateLimiter: RateLimiter?
    private let backoffBaseMs: Int64
    private let logger: EncatchLogger

    init(
        storage: EncatchStorage,
        rateLimiter: RateLimiter? = nil,
        backoffBaseMs: Int64 = RetryQueue.baseBackoffMs,
        logger: EncatchLogger = DefaultEncatchLogger(debugMode: { false })
    ) {
        self.storage = storage
        self.rateLimiter = rateLimiter
        self.backoffBaseMs = backoffBaseMs
        self.logger = logger
    }

    private var isPaused: Bool { rateLimiter?.isPaused(Self.bucket) ?? false }

    /// True for a status-based failure whose message embeds a 4xx status code.
    private func isClientError(_ error: Error) -> Bool {
        guard let apiError = error as? EncatchApiException else { return false }
        return (400...499).contains(apiError.status)
    }

    private func backoffMs(_ retries: Int) -> Int64 {
        backoffBaseMs * Int64(1 << retries)
    }

    private func persistQueue() {
        let serializable = queue.map {
            SerializableQueueItem(id: $0.id, retries: $0.retries, maxRetries: $0.maxRetries, createdAt: $0.createdAt, label: $0.label)
        }
        guard let data = try? JSONEncoder().encode(serializable), let json = String(data: data, encoding: .utf8) else {
            return
        }
        storage.setRetryQueueRaw(json)
    }

    func enqueue(
        label: String,
        maxRetries: Int = RetryQueue.maxRetries,
        onDrop: (@Sendable () -> Void)? = nil,
        fn: @escaping () async throws -> Void
    ) {
        let item = QueuedRequest(
            id: "\(currentTimeMillis())-\(nextId)",
            fn: fn,
            retries: 0,
            maxRetries: maxRetries,
            createdAt: currentTimeMillis(),
            label: label,
            onDrop: onDrop
        )
        nextId += 1
        queue.append(item)
        persistQueue()
        Task { await flush() }
    }

    /// Sends every queued request that is due, one at a time, in order. If a flush is already
    /// running, this joins it and makes it go through the queue once more.
    func flush() async {
        if let running {
            runAgain = true
            await running.value
            return
        }
        let task = Task { await self.runLoop() }
        running = task
        await task.value
    }

    private func runLoop() async {
        repeat {
            runAgain = false
            await runOnce()
        } while runAgain
        running = nil
        scheduleWake()
    }

    private func runOnce() async {
        for item in queue {
            // paused after a 429: hold the rest until the pause ends (the pause end flushes)
            if isPaused { return }
            guard queue.contains(where: { $0.id == item.id }) else { continue }
            if item.nextAttemptAtMs > monotonicNowMs() { continue }

            inFlightIds.insert(item.id)
            do {
                try await item.fn()
                inFlightIds.remove(item.id)
                removedInFlightIds.remove(item.id)
                queue.removeAll { $0.id == item.id }
                persistQueue()
            } catch {
                inFlightIds.remove(item.id)
                if removedInFlightIds.remove(item.id) != nil {
                    // removed while it was being sent, then failed: not retried
                    item.onDrop?()
                    logger.debug("Retry queue: dropping \"\(item.label)\" (removed while in flight, then failed)")
                    continue
                }
                let apiError = error as? EncatchApiException
                if apiError?.status == 429 {
                    drop(item)
                    persistQueue()
                    rateLimiter?.pauseFor(Self.bucket, retryAfterMs: apiError?.retryAfterMs)
                    logger.debug("Retry queue: dropping \"\(item.label)\" (rate limited, not retried)")
                    return
                }
                if isClientError(error) {
                    drop(item)
                    persistQueue()
                    logger.debug("Retry queue: dropping \"\(item.label)\" (client error, no retry)")
                    continue
                }
                item.retries += 1
                if item.retries >= item.maxRetries {
                    drop(item)
                    logger.debug("Retry queue: dropping \"\(item.label)\" after \(item.maxRetries) attempts")
                } else {
                    item.nextAttemptAtMs = monotonicNowMs() + backoffMs(item.retries)
                }
                persistQueue()
            }
        }
    }

    /// Arranges a flush for when the earliest backed-off item becomes due.
    private func scheduleWake() {
        wakeTask?.cancel()
        wakeTask = nil
        // empty, or paused: the pause end flushes
        guard !queue.isEmpty, !isPaused else { return }
        let next = queue.map(\.nextAttemptAtMs).min() ?? 0
        let delayMs = max(0, next - monotonicNowMs())
        wakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            guard !Task.isCancelled, let self else { return }
            await self.flush()
        }
    }

    /// Removes an item that did not succeed and tells its owner.
    private func drop(_ item: QueuedRequest) {
        queue.removeAll { $0.id == item.id }
        item.onDrop?()
    }

    /// Removes queued items with this label; returns how many. One being sent right now is not
    /// reported as dropped: it may still succeed. If it fails, it is dropped (and reported) then,
    /// never retried.
    @discardableResult
    func removeQueued(label: String) -> Int {
        let matching = queue.filter { $0.label == label }
        let inFlight = matching.filter { inFlightIds.contains($0.id) }
        for item in inFlight {
            removedInFlightIds.insert(item.id)
            queue.removeAll { $0.id == item.id }
        }
        let waiting = matching.filter { !inFlightIds.contains($0.id) }
        for item in waiting { drop(item) }
        if !matching.isEmpty { persistQueue() }
        return waiting.count
    }

    /// Forgets every queued item (clearAll): nothing more is sent.
    func clear() {
        removedInFlightIds.formUnion(inFlightIds)
        queue.removeAll()
        wakeTask?.cancel()
        wakeTask = nil
        persistQueue()
    }

    func queueSize() -> Int {
        queue.count
    }
}
