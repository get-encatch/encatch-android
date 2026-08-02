import Foundation

/// Key-value persistence layer, mirroring `storage.ts` from the RN SDK. All keys are
/// namespaced under `@encatch/`. Backed by `UserDefaults` — this matches the Kotlin source's use
/// of `Settings`/SharedPreferences, which is also non-secure storage, so `UserDefaults` is the
/// correct parity choice (not Keychain).
public final class EncatchStorage: @unchecked Sendable {
    private static let keyDeviceId = "@encatch/device_id"
    private static let keyUserName = "@encatch/user_name"
    private static let keyUserIdPrefix = "@encatch/user_id_"
    private static let keyFtPrefix = "@encatch/ft_"
    private static let keyPreferences = "@encatch/preferences"
    private static let keySessionStopped = "@encatch/session_stopped"
    private static let keyRetryQueue = "@encatch/retry_queue"

    private let defaults: UserDefaults
    private let lock = NSLock()

    // Session ID is in-memory only — reset when the app process ends.
    private var inMemorySessionId: String?

    public init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    public func getOrCreateDeviceId() -> String {
        if let stored = defaults.string(forKey: Self.keyDeviceId) {
            return stored
        }
        let id = uuidV7()
        defaults.set(id, forKey: Self.keyDeviceId)
        return id
    }

    public func getOrCreateSessionId() -> String {
        lock.lock()
        defer { lock.unlock() }
        if let inMemorySessionId {
            return inMemorySessionId
        }
        let id = uuidV7()
        inMemorySessionId = id
        return id
    }

    public func clearSession() {
        lock.lock()
        inMemorySessionId = nil
        lock.unlock()
    }

    public func getUserName() -> String? {
        defaults.string(forKey: Self.keyUserName)
    }

    public func setUserName(_ name: String) {
        defaults.set(name, forKey: Self.keyUserName)
    }

    public func clearUserName() {
        defaults.removeObject(forKey: Self.keyUserName)
    }

    public func getUserId(userName: String) -> String? {
        defaults.string(forKey: Self.keyUserIdPrefix + userName)
    }

    public func setUserId(userName: String, userId: String) {
        defaults.set(userId, forKey: Self.keyUserIdPrefix + userName)
    }

    public func clearUserId(userName: String) {
        defaults.removeObject(forKey: Self.keyUserIdPrefix + userName)
    }

    private func ftKey(_ identityKey: String) -> String { Self.keyFtPrefix + identityKey }

    public func getFeedbackTransactions(identityKey: String) -> String? {
        defaults.string(forKey: ftKey(identityKey))
    }

    public func setFeedbackTransactions(identityKey: String, value: String) {
        defaults.set(value, forKey: ftKey(identityKey))
    }

    public func clearFeedbackTransactions(identityKey: String) {
        defaults.removeObject(forKey: ftKey(identityKey))
    }

    public struct Preferences: Sendable {
        public var locale: String?
        public var country: String?

        public init(locale: String? = nil, country: String? = nil) {
            self.locale = locale
            self.country = country
        }
    }

    public func getPreferences() -> Preferences {
        guard let raw = defaults.string(forKey: Self.keyPreferences),
              let data = raw.data(using: .utf8),
              let map = try? JSONDecoder().decode([String: String].self, from: data) else {
            return Preferences()
        }
        return Preferences(locale: map["locale"], country: map["country"])
    }

    public func setPreferences(locale: String? = nil, country: String? = nil) {
        let current = getPreferences()
        let merged = Preferences(locale: locale ?? current.locale, country: country ?? current.country)
        var map: [String: String] = [:]
        if let locale = merged.locale { map["locale"] = locale }
        if let country = merged.country { map["country"] = country }
        guard let data = try? JSONEncoder().encode(map), let json = String(data: data, encoding: .utf8) else {
            return
        }
        defaults.set(json, forKey: Self.keyPreferences)
    }

    public func clearPreferences() {
        defaults.removeObject(forKey: Self.keyPreferences)
    }

    public func getSessionStopped() -> Bool {
        defaults.string(forKey: Self.keySessionStopped) == "true"
    }

    public func setSessionStopped() {
        defaults.set("true", forKey: Self.keySessionStopped)
    }

    public func clearSessionStopped() {
        defaults.removeObject(forKey: Self.keySessionStopped)
    }

    public func getRetryQueueRaw() -> String? {
        defaults.string(forKey: Self.keyRetryQueue)
    }

    public func setRetryQueueRaw(_ json: String) {
        defaults.set(json, forKey: Self.keyRetryQueue)
    }

    public func clearRetryQueue() {
        defaults.removeObject(forKey: Self.keyRetryQueue)
    }

    /// Wipes every `@encatch/`-namespaced key — used by `Encatch.clearAll`.
    public func clearAll() {
        defaults.removeObject(forKey: Self.keyDeviceId)
        defaults.removeObject(forKey: Self.keyUserName)
        defaults.removeObject(forKey: Self.keyPreferences)
        defaults.removeObject(forKey: Self.keySessionStopped)
        defaults.removeObject(forKey: Self.keyRetryQueue)
        for key in defaults.dictionaryRepresentation().keys
        where key.hasPrefix(Self.keyUserIdPrefix) || key.hasPrefix(Self.keyFtPrefix) {
            defaults.removeObject(forKey: key)
        }
        lock.lock()
        inMemorySessionId = nil
        lock.unlock()
    }
}
