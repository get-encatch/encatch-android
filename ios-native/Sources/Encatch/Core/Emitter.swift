import Foundation

/// Minimal typed pub/sub, mirroring `emitter.ts`'s `TypedEmitter` (and the Kotlin `Emitter<T>`). No
/// external dependency.
///
/// Deviation from the Kotlin source: Swift closures aren't `Equatable`, so `off(listener)` (which in
/// Kotlin relies on reference-equality of the function value) can't be ported literally. `on` instead
/// returns an opaque `Subscription` token that callers use to unsubscribe — the same shape as the
/// unsubscribe closure Kotlin's `on` already returns (`() -> Unit`), just without also supporting a
/// separate `off(originalListener)` overload.
public final class Emitter<T>: @unchecked Sendable {
    public typealias Listener = (T) -> Void
    public typealias Unsubscribe = () -> Void

    private struct Entry {
        let id: UUID
        let listener: Listener
    }

    private let lock = NSLock()
    private var listeners: [Entry] = []

    public init() {}

    /// Registers a listener; returns a closure that removes it (mirrors Kotlin's `on` return value).
    @discardableResult
    public func on(_ listener: @escaping Listener) -> Unsubscribe {
        let id = UUID()
        lock.lock()
        listeners.append(Entry(id: id, listener: listener))
        lock.unlock()
        return { [weak self] in self?.off(id: id) }
    }

    private func off(id: UUID) {
        lock.lock()
        listeners.removeAll { $0.id == id }
        lock.unlock()
    }

    public func emit(_ payload: T) {
        // Iterate over a snapshot so listeners can safely unsubscribe during emit.
        lock.lock()
        let snapshot = listeners
        lock.unlock()
        for entry in snapshot {
            entry.listener(payload)
        }
    }

    public func removeAllListeners() {
        lock.lock()
        listeners.removeAll()
        lock.unlock()
    }
}

/// Internal event bridging `Encatch` to the UI layer's WebView/inline form UI.
public enum InternalEvent: Sendable {
    case showForm(ShowFormPayload)
    case dismissForm(formConfigurationId: String?)
    case sendToWebView(SDKMessage)
    case userIdentified(userName: String?, userId: String?)
}

public struct ShowFormPayload: Sendable {
    public var formId: String
    public var formConfig: ShowFormResponse
    public var resetMode: ResetMode
    public var triggerType: TriggerType
    public var prefillResponses: [String: JSONValue]
    public var locale: String?
    public var theme: Theme?
    public var context: [String: JSONValue]?
    /// Resolved presentation target: "inline" renders in an inline slot, "modal" in the overlay dialog.
    public var presentation: String
    public var inlineSlotId: String?

    public init(
        formId: String,
        formConfig: ShowFormResponse,
        resetMode: ResetMode,
        triggerType: TriggerType,
        prefillResponses: [String: JSONValue] = [:],
        locale: String? = nil,
        theme: Theme? = nil,
        context: [String: JSONValue]? = nil,
        presentation: String = "modal",
        inlineSlotId: String? = nil
    ) {
        self.formId = formId
        self.formConfig = formConfig
        self.resetMode = resetMode
        self.triggerType = triggerType
        self.prefillResponses = prefillResponses
        self.locale = locale
        self.theme = theme
        self.context = context
        self.presentation = presentation
        self.inlineSlotId = inlineSlotId
    }
}

/// Shared internal emitter singleton, mirrors the RN SDK's exported `_internalEmitter`.
public enum EncatchInternalEmitter {
    public static let shared = Emitter<InternalEvent>()
}
