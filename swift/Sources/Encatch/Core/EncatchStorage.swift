import Foundation

/// Key-value persistence layer, mirroring `storage.ts` from the RN SDK. All keys are
/// namespaced under `@encatch/`. Backed by `UserDefaults` — this matches the Kotlin source's use
/// of `Settings`/SharedPreferences, which is also non-secure storage, so `UserDefaults` is the
/// correct parity choice (not Keychain).
///
/// The user's data (user name, user ids, secure signatures, survey history) goes to `UserDefaults`
/// by default, so it survives a restart; with `persistUserAcrossRestarts` off it goes to a
/// `MemoryUserStore` for the current app run only (RN 1.5.0 item 4). Everything else (device id, a
/// saved stopSession(), preferences, the retry queue's labels) is always saved.
public final class EncatchStorage: @unchecked Sendable {
    private static let keyDeviceId = "@encatch/device_id"
    private static let keyUserName = "@encatch/user_name"
    private static let keyUserIdPrefix = "@encatch/user_id_"
    private static let keyUserSignaturePrefix = "@encatch/user_signature_"
    private static let keyFtPrefix = "@encatch/ft_"
    private static let keyPreferences = "@encatch/preferences"
    private static let keySessionStopped = "@encatch/session_stopped"
    private static let keyRetryQueue = "@encatch/retry_queue"

    private let defaults: UserDefaults
    /// The user's data for this app run when `persistUserAcrossRestarts` is off; nil: `defaults`.
    private let userMemory: MemoryUserStore?
    private let lock = NSLock()

    // Session ID is in-memory only — reset when the app process ends.
    private var inMemorySessionId: String?

    public convenience init(defaults: UserDefaults = .standard) {
        self.init(defaults: defaults, userMemory: nil)
    }

    init(defaults: UserDefaults, userMemory: MemoryUserStore?) {
        self.defaults = defaults
        self.userMemory = userMemory
    }

    private func userString(_ key: String) -> String? {
        if let userMemory { return userMemory.get(key) }
        return defaults.string(forKey: key)
    }

    private func setUserString(_ value: String, forKey key: String) {
        if let userMemory { userMemory.set(value, forKey: key) } else { defaults.set(value, forKey: key) }
    }

    private func removeUserString(forKey key: String) {
        if let userMemory { userMemory.remove(key) } else { defaults.removeObject(forKey: key) }
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
        userString(Self.keyUserName)
    }

    public func setUserName(_ name: String) {
        setUserString(name, forKey: Self.keyUserName)
    }

    public func clearUserName() {
        removeUserString(forKey: Self.keyUserName)
    }

    public func getUserId(userName: String) -> String? {
        userString(Self.keyUserIdPrefix + userName)
    }

    public func setUserId(userName: String, userId: String) {
        setUserString(userId, forKey: Self.keyUserIdPrefix + userName)
    }

    public func clearUserId(userName: String) {
        removeUserString(forKey: Self.keyUserIdPrefix + userName)
    }

    /// The signature of a user's last successful secure identify, and its time (session-time keys).
    struct StoredSignature: Codable, Equatable, Sendable {
        let signature: String
        let time: String?
    }

    func getUserSignature(userName: String) -> StoredSignature? {
        guard let raw = userString(Self.keyUserSignaturePrefix + userName),
              let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(StoredSignature.self, from: data)
    }

    func setUserSignature(userName: String, value: StoredSignature) {
        guard let data = try? JSONEncoder().encode(value), let json = String(data: data, encoding: .utf8) else { return }
        setUserString(json, forKey: Self.keyUserSignaturePrefix + userName)
    }

    func clearUserSignature(userName: String) {
        removeUserString(forKey: Self.keyUserSignaturePrefix + userName)
    }

    private func ftKey(_ identityKey: String) -> String { Self.keyFtPrefix + identityKey }

    public func getFeedbackTransactions(identityKey: String) -> String? {
        userString(ftKey(identityKey))
    }

    public func setFeedbackTransactions(identityKey: String, value: String) {
        setUserString(value, forKey: ftKey(identityKey))
    }

    public func clearFeedbackTransactions(identityKey: String) {
        removeUserString(forKey: ftKey(identityKey))
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

    /// Deletes the user's data saved on the device (user name, user ids, signatures, survey
    /// history): with `persistUserAcrossRestarts` off, what an earlier run saved is not kept.
    func deleteSavedUserData() {
        defaults.removeObject(forKey: Self.keyUserName)
        for key in defaults.dictionaryRepresentation().keys
        where key.hasPrefix(Self.keyUserIdPrefix) || key.hasPrefix(Self.keyFtPrefix)
            || key.hasPrefix(Self.keyUserSignaturePrefix) {
            defaults.removeObject(forKey: key)
        }
    }

    /// Wipes every `@encatch/`-namespaced key — used by `Encatch.clearAll`.
    public func clearAll() {
        defaults.removeObject(forKey: Self.keyDeviceId)
        defaults.removeObject(forKey: Self.keyPreferences)
        defaults.removeObject(forKey: Self.keySessionStopped)
        defaults.removeObject(forKey: Self.keyRetryQueue)
        deleteSavedUserData()
        userMemory?.clear()
        lock.lock()
        inMemorySessionId = nil
        lock.unlock()
    }
}

/// Holds the user's data for the current app run when `persistUserAcrossRestarts` is off.
final class MemoryUserStore: @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()

    func get(_ key: String) -> String? {
        lock.lock()
        defer { lock.unlock() }
        return values[key]
    }

    func set(_ value: String, forKey key: String) {
        lock.lock()
        values[key] = value
        lock.unlock()
    }

    func remove(_ key: String) {
        lock.lock()
        values[key] = nil
        lock.unlock()
    }

    func clear() {
        lock.lock()
        values = [:]
        lock.unlock()
    }
}
