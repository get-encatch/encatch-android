import Foundation

/// A one-shot signal: fired once, awaited by any number of tasks (Kotlin's `CompletableDeferred<Unit>`).
/// Used by setUserProperties to wait for a pending identify, a survey to close, or stop() / clearAll().
final class AsyncSignal: @unchecked Sendable {
    private let lock = NSLock()
    private var fired = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var callbacks: [UUID: @Sendable () -> Void] = [:]

    var isFired: Bool {
        lock.lock()
        defer { lock.unlock() }
        return fired
    }

    /// Fires the signal (again: nothing), waking every waiter.
    func fire() {
        lock.lock()
        guard !fired else {
            lock.unlock()
            return
        }
        fired = true
        let waiters = self.waiters
        let callbacks = Array(self.callbacks.values)
        self.waiters = []
        self.callbacks = [:]
        lock.unlock()
        for waiter in waiters { waiter.resume() }
        for callback in callbacks { callback() }
    }

    /// Returns once the signal has fired.
    func wait() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            lock.lock()
            if fired {
                lock.unlock()
                continuation.resume()
            } else {
                waiters.append(continuation)
                lock.unlock()
            }
        }
    }

    /// Calls [callback] when the signal fires (at once if it has); returns a key to remove it with.
    @discardableResult
    func onFire(_ callback: @escaping @Sendable () -> Void) -> UUID? {
        lock.lock()
        if fired {
            lock.unlock()
            callback()
            return nil
        }
        let key = UUID()
        callbacks[key] = callback
        lock.unlock()
        return key
    }

    func removeCallback(_ key: UUID?) {
        guard let key else { return }
        lock.lock()
        callbacks[key] = nil
        lock.unlock()
    }

    /// Returns once [a] or [b] has fired; leaves no callback behind on either.
    static func either(_ a: AsyncSignal, _ b: AsyncSignal) async {
        let either = AsyncSignal()
        let keyA = a.onFire { either.fire() }
        let keyB = b.onFire { either.fire() }
        await either.wait()
        a.removeCallback(keyA)
        b.removeCallback(keyB)
    }
}

/// Runs [body] once, whichever caller gets there first.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    private let body: @Sendable () -> Void

    init(_ body: @escaping @Sendable () -> Void) { self.body = body }

    func callAsFunction() {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { body() }
    }
}
