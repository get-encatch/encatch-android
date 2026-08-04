import Foundation

/// Owns the 30s ping loop, mirroring the `_startPingInterval`/`_scheduleNextPing`/
/// `_handleResponseMeta` trio in `encatch.ts`. Ping is suppressed while a form is visible
/// and can be rescheduled/stopped based on server-driven `pingAgainIn`/`pingOnNextPageVisit`.
final class SessionManager: @unchecked Sendable {
    static let pingIntervalMs: Int64 = 30_000

    private let isFormVisible: @Sendable () -> Bool
    private let onPing: @Sendable () async -> Void

    private let lock = NSLock()
    private var pingTask: Task<Void, Never>?
    private var pingActive = false

    var isPingActive: Bool {
        lock.lock()
        defer { lock.unlock() }
        return pingActive
    }

    init(isFormVisible: @escaping @Sendable () -> Bool, onPing: @escaping @Sendable () async -> Void) {
        self.isFormVisible = isFormVisible
        self.onPing = onPing
    }

    func startPingInterval() {
        stopPingInterval()
        lock.lock()
        pingActive = true
        lock.unlock()
        pingTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(Self.pingIntervalMs) * 1_000_000)
                if Task.isCancelled { return }
                if !self.isFormVisible() {
                    await self.onPing()
                }
            }
        }
    }

    func stopPingInterval() {
        lock.lock()
        pingActive = false
        lock.unlock()
        pingTask?.cancel()
        pingTask = nil
    }

    /// Cancels the ping loop and actually waits for its in-flight iteration (if any) to finish,
    /// unlike `stopPingInterval()` which only requests cancellation and returns immediately.
    /// Callers that are about to reassign shared mutable state the ping loop's `onPing` touches
    /// (see `Encatch.initialize`'s reconfigure path) need this to close the race window where the
    /// old loop's already-running `await onPing()` call keeps executing after cancellation.
    func stopPingIntervalAndWait() async {
        let task = clearPingTaskForStop()
        task?.cancel()
        await task?.value
    }

    /// Synchronous helper so the lock is never held across an `async` function's suspension
    /// points (NSLock's lock/unlock are unavailable from async contexts under strict concurrency).
    private func clearPingTaskForStop() -> Task<Void, Never>? {
        lock.lock()
        defer { lock.unlock() }
        pingActive = false
        let task = pingTask
        pingTask = nil
        return task
    }

    func scheduleNextPing(delayMs: Int64) {
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            guard let self else { return }
            try? await Task.sleep(nanoseconds: UInt64(max(0, delayMs)) * 1_000_000)
            if Task.isCancelled { return }
            if !self.isFormVisible() {
                await self.onPing()
            }
            self.startPingInterval()
        }
    }

    /// Applies `pingAgainIn`/`pingOnNextPageVisit` from any API response, mirrors `_handleResponseMeta`.
    func handleResponseMeta(_ meta: ResponseMeta) {
        if let pingAgainIn = meta.pingAgainIn, pingAgainIn > 0, isPingActive {
            scheduleNextPing(delayMs: Int64(pingAgainIn * 1000))
        }
        if meta.pingOnNextPageVisit == false {
            stopPingInterval()
        }
    }
}
