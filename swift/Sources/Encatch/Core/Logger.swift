import Foundation
import os.log

/// Internal logger for the Encatch SDK, mirroring `logger.ts`'s `EncatchLogger`.
public protocol EncatchLogger: Sendable {
    func debug(_ message: String)
    func warn(_ message: String)
}

private let osLog = OSLog(subsystem: "com.encatch.sdk", category: "Encatch")

/// `debug` is gated by `debugMode`; `warn` always logs, matching the RN SDK's fallback logger.
final class DefaultEncatchLogger: EncatchLogger, @unchecked Sendable {
    private let debugMode: @Sendable () -> Bool

    init(debugMode: @escaping @Sendable () -> Bool) {
        self.debugMode = debugMode
    }

    func debug(_ message: String) {
        guard debugMode() else { return }
        os_log("%{public}@", log: osLog, type: .debug, "[Encatch] \(message)")
    }

    func warn(_ message: String) {
        os_log("%{public}@", log: osLog, type: .default, "[Encatch] \(message)")
    }
}
