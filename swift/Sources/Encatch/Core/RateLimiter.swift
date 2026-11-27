import Foundation

/// Timings the SDK runs on. Internal: tests shorten them on a fresh `Encatch` before `initialize`.
struct EncatchTimings: Sendable {
    /// The regular background ping.
    var pingIntervalMs: Int64 = 30_000
    /// Identical trackEvent / trackScreen calls within this window are sent once.
    var collapseWindowMs: Int64 = 1_000
    /// Retry-queue backoff: base × 2^retries, so 2 s then 4 s.
    var retryBackoffBaseMs: Int64 = 1_000
    /// The longest a single API request may take.
    var requestTimeoutMs: Int64 = 30_000
    /// A 429 without a (valid) Retry-After pauses this long…
    var defaultPauseMs: Int64 = 60_000
    /// …and no pause is longer than this, whatever Retry-After says.
    var maxPauseMs: Int64 = 5 * 60_000
    /// How often setUserProperties' shared watcher checks that an open survey closed (a close also wakes it).
    var surveyIdlePollMs: Int64 = 500
}

/// Monotonic milliseconds (not wall time), for windows and pauses.
func monotonicNowMs() -> Int64 {
    Int64(DispatchTime.now().uptimeNanoseconds / 1_000_000)
}

/// Groups of calls the gateway counts together (RN 1.4.4 `rate-limit.ts`).
enum RateLimitBucket: String, Sendable {
    /// identify-user, track-event (survey events too), track-screen, ping
    case tracking
    case showForm = "show-form"
    case submitForm = "submit-form"
    /// everything else: never paused
    case other

    /// The bucket of an endpoint path such as `engage-product/encatch/api/v2/encatch/track-event`.
    static func of(endpoint: String) -> RateLimitBucket {
        switch endpoint.split(separator: "/").last.map(String.init) ?? endpoint {
        case "identify-user", "track-event", "track-screen", "ping": return .tracking
        case "show-form": return .showForm
        case "submit-form": return .submitForm
        default: return .other
        }
    }
}

/// Parses a Retry-After header: a delay in seconds, or an HTTP date. Milliseconds from `now`, or
/// nil when missing or invalid.
func parseRetryAfterMs(_ value: String?, now: Date = Date()) -> Int64? {
    guard let trimmed = value?.trimmingCharacters(in: .whitespaces), !trimmed.isEmpty else { return nil }
    if trimmed.range(of: #"^\d+(\.\d+)?$"#, options: .regularExpression) != nil, let seconds = Double(trimmed) {
        return Int64((seconds * 1000).rounded())
    }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.timeZone = TimeZone(identifier: "GMT")
    for format in ["EEE, dd MMM yyyy HH:mm:ss zzz", "EEEE, dd-MMM-yy HH:mm:ss zzz", "EEE MMM d HH:mm:ss yyyy"] {
        formatter.dateFormat = format
        if let date = formatter.date(from: trimmed) {
            return max(0, Int64((date.timeIntervalSince(now) * 1000).rounded()))
        }
    }
    return nil
}

/// The 429 pause (RN 1.4.4 item 2): after a 429 a bucket sends nothing new until Retry-After has
/// passed (default 60 s, at most 5 min). One warning per pause. Listeners hear when a pause ends.
final class RateLimiter: @unchecked Sendable {
    private let lock = NSLock()
    private var pausedUntilMs: [RateLimitBucket: Int64] = [:]
    private var timers: [RateLimitBucket: Task<Void, Never>] = [:]
    private var listeners: [@Sendable (RateLimitBucket) -> Void] = []
    private let defaultPauseMs: Int64
    private let maxPauseMs: Int64
    private let logger: EncatchLogger

    init(defaultPauseMs: Int64 = 60_000, maxPauseMs: Int64 = 300_000, logger: EncatchLogger = DefaultEncatchLogger(debugMode: { false })) {
        self.defaultPauseMs = defaultPauseMs
        self.maxPauseMs = maxPauseMs
        self.logger = logger
    }

    /// Starts (or extends) a pause after a 429.
    func pauseFor(_ bucket: RateLimitBucket, retryAfterMs: Int64?) {
        guard bucket != .other else { return }
        let requested = retryAfterMs.flatMap { $0 >= 0 ? $0 : nil } ?? defaultPauseMs
        let ms = min(maxPauseMs, requested)
        let now = monotonicNowMs()
        let until = now + ms
        let (started, previousTimer): (Bool?, Task<Void, Never>?) = sync {
            let current = pausedUntilMs[bucket] ?? 0
            if until <= current { return (nil, nil) }
            pausedUntilMs[bucket] = until
            let previous = timers[bucket]
            timers[bucket] = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(ms) * 1_000_000)
                guard !Task.isCancelled, let self else { return }
                self.pauseEnded(bucket)
            }
            return (current <= now, previous)
        }
        previousTimer?.cancel()
        if started == true {
            logger.warn(
                "Rate limited (429): pausing \(bucket.rawValue) requests for \(Int((Double(ms) / 1000).rounded(.up))) s. " +
                    "Calls made during the pause are skipped."
            )
        }
    }

    func isPaused(_ bucket: RateLimitBucket) -> Bool { remainingMs(bucket) > 0 }

    /// Milliseconds until the bucket's pause ends (0 when not paused).
    func remainingMs(_ bucket: RateLimitBucket) -> Int64 {
        let until = sync { pausedUntilMs[bucket] ?? 0 }
        return max(0, until - monotonicNowMs())
    }

    func onPauseEnd(_ listener: @escaping @Sendable (RateLimitBucket) -> Void) {
        sync { listeners.append(listener) }
    }

    /// Clears every pause (clearAll, re-initialize).
    func clear() {
        let pending: [Task<Void, Never>] = sync {
            pausedUntilMs.removeAll()
            let pending = Array(timers.values)
            timers.removeAll()
            return pending
        }
        pending.forEach { $0.cancel() }
    }

    private func pauseEnded(_ bucket: RateLimitBucket) {
        let current: [@Sendable (RateLimitBucket) -> Void] = sync {
            timers[bucket] = nil
            return listeners
        }
        for listener in current { listener(bucket) }
    }

    private func sync<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }
}
