import Foundation

/// Offline retry queue for failed, idempotent API calls (identifyUser/trackEvent/trackScreen),
/// mirroring `retry-queue.ts`. Persists metadata only — the closure itself is not
/// reconstructable across process death, matching the RN SDK's own limitation.
///
/// Ported as a Swift `actor` rather than a class guarded by a Kotlin `Mutex` — the idiomatic Swift
/// equivalent for the same "all queue mutation is serialized" guarantee.
actor RetryQueue {
    static let maxRetries = 3
    static let baseBackoffMs: Int64 = 1000

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

        init(id: String, fn: @escaping () async throws -> Void, retries: Int, maxRetries: Int, createdAt: Int64, label: String) {
            self.id = id
            self.fn = fn
            self.retries = retries
            self.maxRetries = maxRetries
            self.createdAt = createdAt
            self.label = label
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

    init(storage: EncatchStorage) {
        self.storage = storage
    }

    /// True for a status-based failure whose message embeds a 4xx status code.
    private func isClientError(_ error: Error) -> Bool {
        guard let apiError = error as? EncatchApiException else { return false }
        return (400...499).contains(apiError.status)
    }

    private func backoffMs(_ retries: Int) -> Int64 {
        Self.baseBackoffMs * Int64(1 << retries)
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

    func enqueue(label: String, maxRetries: Int = RetryQueue.maxRetries, fn: @escaping () async throws -> Void) {
        let item = QueuedRequest(
            id: "\(currentTimeMillis())-\(nextId)",
            fn: fn,
            retries: 0,
            maxRetries: maxRetries,
            createdAt: currentTimeMillis(),
            label: label
        )
        nextId += 1
        queue.append(item)
        persistQueue()
        Task { await flush() }
    }

    func flush() async {
        let snapshot = queue
        for item in snapshot {
            await attempt(item)
        }
    }

    private func attempt(_ item: QueuedRequest) async {
        guard queue.contains(where: { $0.id == item.id }), !inFlightIds.contains(item.id) else { return }
        inFlightIds.insert(item.id)
        defer { inFlightIds.remove(item.id) }
        do {
            try await item.fn()
            queue.removeAll { $0.id == item.id }
            persistQueue()
        } catch {
            if isClientError(error) {
                queue.removeAll { $0.id == item.id }
                persistQueue()
                return
            }
            item.retries += 1
            if item.retries >= item.maxRetries {
                queue.removeAll { $0.id == item.id }
                persistQueue()
            } else {
                persistQueue()
                let delayMs = backoffMs(item.retries)
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
                    await self.attempt(item)
                }
            }
        }
    }

    func queueSize() -> Int {
        queue.count
    }
}
