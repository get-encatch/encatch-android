import Foundation

/// Owns the background ping, mirroring `_startPingInterval`/`_scheduleNextPing`/`_handleResponseMeta`
/// of RN 1.4.4 `encatch.ts` (items 18, 19). Ping is suppressed while a form is visible.
///
/// - Pinging stays "active" while waiting for a server-scheduled ping (`pingAgainIn`), so every
///   reply's `pingAgainIn` is honoured — the scheduled ping's own reply too.
/// - After a server-scheduled ping, the regular interval resumes only if pinging is still active
///   (not stopped meanwhile) and the reply did not schedule the next ping.
/// - Each ping runs in its own task, so cancelling a timer never cancels a request in flight.
final class SessionManager: @unchecked Sendable {
    static let pingIntervalMs: Int64 = 30_000

    let pingIntervalMs: Int64
    private let isFormVisible: @Sendable () -> Bool
    private let onPing: @Sendable () async -> Void

    private let lock = NSLock()
    private var intervalTask: Task<Void, Never>?
    private var timeoutTask: Task<Void, Never>?
    /// Which scheduled ping [timeoutTask] is, so a fired one can detach only itself.
    private var timeoutToken: UUID?
    private var pingActive = false
    /// The ping request in flight, if any (awaited by `stopPingIntervalAndWait`).
    private var inFlightPing: Task<Void, Never>?

    var isPingActive: Bool { sync { pingActive } }

    init(
        pingIntervalMs: Int64 = SessionManager.pingIntervalMs,
        isFormVisible: @escaping @Sendable () -> Bool,
        onPing: @escaping @Sendable () async -> Void
    ) {
        self.pingIntervalMs = pingIntervalMs
        self.isFormVisible = isFormVisible
        self.onPing = onPing
    }

    func startPingInterval() {
        let old: [Task<Void, Never>] = sync {
            let old = takeTimers()
            pingActive = true
            intervalTask = makeIntervalTask()
            return old
        }
        old.forEach { $0.cancel() }
    }

    func stopPingInterval() {
        let old: [Task<Void, Never>] = sync {
            pingActive = false
            return takeTimers()
        }
        old.forEach { $0.cancel() }
    }

    /// Cancels the timers and waits for an in-flight ping (if any) to finish, unlike
    /// `stopPingInterval()` which returns at once. Callers about to reassign shared state the ping
    /// touches (see `Encatch.initialize`'s reconfigure path) need this.
    func stopPingIntervalAndWait() async {
        let (old, ping): ([Task<Void, Never>], Task<Void, Never>?) = sync {
            pingActive = false
            return (takeTimers(), inFlightPing)
        }
        for task in old {
            task.cancel()
            await task.value
        }
        await ping?.value
    }

    /// The server asked for the next ping in `delayMs` (pingAgainIn). Nothing unless pinging is
    /// active (stopped, paused, full screen); leaves "active" as it is.
    func scheduleNextPing(delayMs: Int64) {
        let token = UUID()
        let old: [Task<Void, Never>] = sync {
            guard pingActive else { return [] }
            let old = takeTimers()
            timeoutToken = token
            timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(max(0, delayMs)) * 1_000_000)
                guard !Task.isCancelled, let self else { return }
                // detach first: the reply may schedule the next ping, which must not cancel this one
                self.sync {
                    if self.timeoutToken == token {
                        self.timeoutTask = nil
                        self.timeoutToken = nil
                    }
                }
                if !self.isFormVisible() { await self.runPing() }
                // back to the regular interval, unless the reply already scheduled the next ping or
                // pinging was stopped (stopSession / pauseSession / pingOnNextPageVisit) meanwhile
                self.sync {
                    if self.pingActive && self.timeoutTask == nil && self.intervalTask == nil {
                        self.intervalTask = self.makeIntervalTask()
                    }
                }
            }
            return old
        }
        old.forEach { $0.cancel() }
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

    // MARK: - private

    /// Call under [lock].
    private func makeIntervalTask() -> Task<Void, Never> {
        let interval = pingIntervalMs
        return Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(interval) * 1_000_000)
                guard !Task.isCancelled, let self else { return }
                if !self.isFormVisible() { await self.runPing() }
            }
        }
    }

    /// Call under [lock]: removes both timers and returns them for cancelling outside the lock.
    private func takeTimers() -> [Task<Void, Never>] {
        let old = [intervalTask, timeoutTask].compactMap { $0 }
        intervalTask = nil
        timeoutTask = nil
        timeoutToken = nil
        return old
    }

    /// The ping in a task of its own: a timer cancelled meanwhile does not cancel the request.
    private func runPing() async {
        let onPing = self.onPing
        let ping = Task { await onPing() }
        sync { inFlightPing = ping }
        await ping.value
        sync { if inFlightPing == ping { inFlightPing = nil } }
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
