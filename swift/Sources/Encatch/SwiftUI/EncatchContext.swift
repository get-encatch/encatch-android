import Foundation
import Combine

/// The SDK's state and calls for SwiftUI views, the counterpart of React Native's `useEncatch()`
/// (`EncatchContextValue`): same values, same methods. `EncatchProvider` creates one and hands it
/// to the views inside it:
///
/// ```swift
/// struct ProfileView: View {
///     @EnvironmentObject var encatch: EncatchContext
///     var body: some View {
///         Text(encatch.isIdentified ? "Signed in as \(encatch.userName ?? "")" : "Visitor")
///     }
/// }
/// ```
///
/// The published values follow the SDK: `isIdentified` and `userName` change when an identify
/// succeeds and on `resetUser()` / `clearAll()` (also the server's "retries exhausted" reset), as
/// `Encatch.shared.isIdentified()` does. The methods are call-and-forget, like React Native's: they
/// return at once (errors are dropped) and reach the SDK in the order they were made, so
/// `resetUser()` then `identifyUser(…)` signs the new user in. Calls that wait for a server reply
/// don't hold up the others: `showForm` and `dismissForm` run one after another, and so do
/// `submitForm` calls; a sign-in or sign-out (`identifyUser`, `resetUser`) first waits for pending
/// submits (up to 10 seconds), so a submit made before signing out goes as the user who answered.
/// To wait for a call's result, `await` the `Encatch.shared` method instead.
@MainActor
public final class EncatchContext: ObservableObject {
    /// `true` once the provider's `initialize` has finished.
    @Published public private(set) var isInitialized = false
    /// Whether the SDK has a confirmed identity (`Encatch.isIdentified()`).
    @Published public private(set) var isIdentified = false
    /// The identified user's name, or `nil` for a visitor. While switching to another user it stays
    /// the previous user's until the new user's identify succeeds, as `isIdentified` does.
    @Published public private(set) var userName: String?

    let encatch: Encatch
    private let subscription = Subscription()
    /// The calls made through this context, one after the other in call order.
    private var tail: Task<Void, Never>?
    /// showForm and dismissForm, one after the other (each waits for its server reply).
    private var formTail: Task<Void, Never>?
    /// submitForm calls, one after the other; a sign-in or sign-out waits for them.
    private var submitTail: Task<Void, Never>?
    /// The longest a sign-in or sign-out waits for pending submits (shortened by tests).
    static var submitWaitLimitMs: UInt64 = 10_000
    /// The initialization in progress for each SDK instance, shared by every context: two windows'
    /// providers initialize once, and a change of API key waits for the one before it.
    private static var initializing: [ObjectIdentifier: Task<Void, Never>] = [:]

    /// A context for `Encatch.shared`. `EncatchProvider` makes one; create your own only when you
    /// initialize the SDK yourself and want its state in SwiftUI (call `refresh()` after `initialize`).
    public convenience init() {
        self.init(encatch: .shared)
    }

    init(encatch: Encatch) {
        self.encatch = encatch
        subscription.cancel = EncatchInternalEmitter.shared.on { [weak self] event in
            // emitted on whichever thread made the change; the published values change on main
            guard case .userIdentified(let name, let userId) = event else { return }
            // a name without its user id is not a confirmed identity (the SDK keeps the previous one)
            let confirmedName = userId != nil ? name : nil
            Task { @MainActor [weak self] in self?.refresh(identifiedName: confirmedName) }
        }
        refresh()
    }

    /// Reads the SDK's state again (it is also updated on every identity change).
    public func refresh() {
        refresh(identifiedName: nil)
    }

    private func refresh(identifiedName: String?) {
        let initialized = encatch.isInitialized
        let identified = encatch.isIdentified()
        // the name the identity change announced, else (at launch, after a restore) the SDK's; kept
        // as it is while still identified otherwise (a switch in progress keeps the previous user's)
        let name: String? = identified ? (identifiedName ?? userName ?? encatch.userName) : nil
        if isInitialized != initialized { isInitialized = initialized }
        if isIdentified != identified { isIdentified = identified }
        if userName != name { userName = name }
    }

    /// Initializes the SDK unless it already is, with this API key: a provider that appears again,
    /// or another window's provider, doesn't initialize it a second time (that would drop the SDK's
    /// queued calls). Installs the modal form host on iOS, then publishes the SDK's state.
    func initialize(apiKey: String, config: EncatchConfig?) async {
        let sdk = ObjectIdentifier(encatch)
        // one initialization at a time for this SDK, whatever the key; then look again
        while let running = Self.initializing[sdk] {
            await running.value
        }
        if !(encatch.isInitialized && encatch.apiKey == apiKey) {
            let encatch = encatch
            // the task removes itself before it finishes: a context waiting on it then finds no
            // initialization in progress (removing it after `await task.value` instead let a waiter
            // that resumed first see the finished task again and spin)
            let task = Task { @MainActor in
                _ = try? await encatch.initialize(apiKey: apiKey, config: config)
                Self.initializing[sdk] = nil
            }
            Self.initializing[sdk] = task
            await task.value
        }
        #if canImport(UIKit)
        EncatchFormHost.install()
        #endif
        refresh()
    }

    /// Tracks a screen once the SDK is initialized (`.encatchTrackScreen` on a screen that appears
    /// before the provider has finished initializing); nothing if the task is cancelled first.
    func trackScreenWhenInitialized(_ screenName: String) async {
        if !isInitialized {
            for await ready in $isInitialized.values where ready { break }
        }
        guard !Task.isCancelled else { return }
        trackScreen(screenName)
    }

    private enum CallKind {
        /// Returns once the SDK has taken the call (it queues any request itself).
        case quick
        /// Changes who the user is: first waits for the submits made before it (up to
        /// `submitWaitLimitMs`), so they go as the user who answered.
        case identity
        /// showForm / dismissForm: waits for a server reply, so it runs after the form calls before
        /// it without holding up the other calls.
        case form
        /// submitForm: waits for a server reply, so it runs after the submits before it without
        /// holding up the other calls.
        case submit
    }

    /// Runs a call after the ones made before it (see `CallKind`).
    private func enqueue(_ kind: CallKind = .quick, _ op: @escaping @Sendable () async -> Void) {
        let previous = tail
        tail = Task { @MainActor in
            await previous?.value
            switch kind {
            case .quick:
                await op()
            case .identity:
                if let pendingSubmits = self.submitTail {
                    await Self.wait(for: pendingSubmits, atMostMs: Self.submitWaitLimitMs)
                }
                await op()
            case .form:
                let previousForm = self.formTail
                self.formTail = Task {
                    await previousForm?.value
                    await op()
                }
            case .submit:
                let previousSubmit = self.submitTail
                self.submitTail = Task {
                    await previousSubmit?.value
                    await op()
                }
            }
        }
    }

    /// Waits for `task` to finish, or `ms` milliseconds, whichever comes first (the task goes on).
    /// Not a task group: a group returns only once all its children have, and waiting for `task`
    /// can't be cut short.
    private static func wait(for task: Task<Void, Never>, atMostMs ms: UInt64) async {
        let timeout = Task { try? await Task.sleep(nanoseconds: ms * 1_000_000) }
        // ends the wait early when the task finishes first; itself ends with the task, so nothing leaks
        Task {
            await task.value
            timeout.cancel()
        }
        await timeout.value
    }

    // MARK: Calls (React Native's EncatchContextValue methods)

    /// `Encatch.identifyUser`.
    public func identifyUser(userName: String, traits: UserTraits? = nil, options: IdentifyOptions? = nil) {
        let encatch = encatch
        enqueue(.identity) { try? await encatch.identifyUser(userName: userName, traits: traits, options: options) }
    }

    /// `Encatch.setUserProperties`: updates the identified user's properties. It takes its place after
    /// the calls made before it (an `identifyUser` just before it counts), then waits on its own for
    /// that identify and for an open survey, without holding up the calls made after it.
    public func setUserProperties(userName: String, traits: UserTraits) {
        let encatch = encatch
        enqueue { encatch.startSetUserProperties(userName: userName, traits: traits) }
    }

    /// `Encatch.setLocale`.
    public func setLocale(_ locale: String) {
        let encatch = encatch
        enqueue { encatch.setLocale(locale) }
    }

    /// `Encatch.setCountry`.
    public func setCountry(_ country: String) {
        let encatch = encatch
        enqueue { encatch.setCountry(country) }
    }

    /// `Encatch.setTheme`.
    public func setTheme(_ theme: Theme) {
        let encatch = encatch
        enqueue { encatch.setTheme(theme) }
    }

    /// `Encatch.trackEvent`.
    public func trackEvent(_ eventName: String) {
        let encatch = encatch
        enqueue { try? await encatch.trackEvent(eventName) }
    }

    /// `Encatch.trackScreen`.
    public func trackScreen(_ screenName: String) {
        let encatch = encatch
        enqueue { try? await encatch.trackScreen(screenName) }
    }

    /// `Encatch.showForm`.
    public func showForm(_ formId: String, options: ShowFormOptions? = nil) {
        let encatch = encatch
        enqueue(.form) { try? await encatch.showForm(formId, options: options) }
    }

    /// `Encatch.dismissForm`.
    public func dismissForm(_ formConfigurationId: String? = nil) {
        let encatch = encatch
        enqueue(.form) { try? await encatch.dismissForm(formConfigurationId) }
    }

    /// `Encatch.addToResponse`: a question id or slug, and its value.
    public func addToResponse(questionId: String, value: Any?) {
        // Any? isn't Sendable: boxed to be queued (the SDK converts it when the call runs, so a
        // reference-type value changed right after this call may be read changed)
        let encatch = encatch
        let json = SendableValue(value)
        enqueue { encatch.addToResponse(questionId: questionId, value: json.value) }
    }

    /// `Encatch.resetUser`.
    public func resetUser() {
        let encatch = encatch
        enqueue(.identity) { try? await encatch.resetUser() }
    }

    /// `Encatch.on`: returns a closure that unsubscribes (Swift's counterpart of React Native's `off`).
    @discardableResult
    public func on(_ callback: @escaping EventCallback) -> () -> Void {
        encatch.on(callback)
    }

    /// `Encatch.submitForm`, for custom native forms shown from `onBeforeShowForm`.
    public func submitForm(_ params: SubmitFormRequest) {
        let encatch = encatch
        enqueue(.submit) { try? await encatch.submitForm(params) }
    }

    /// `Encatch.emitEvent`, for custom native forms shown from `onBeforeShowForm`.
    public func emitEvent(_ eventType: EventType, _ payload: EventPayload) {
        encatch.emitEvent(eventType, payload)
    }

    /// `Encatch.emitEvent` without a timestamp (the SDK sets it), as React Native's takes it.
    public func emitEvent(_ eventType: EventType, formId: String? = nil, data: [String: JSONValue]? = nil) {
        encatch.emitEvent(eventType, EventPayload(formId: formId, timestamp: 0, data: data))
    }

    /// `Encatch.refineText` (AI enhance), for custom native forms.
    public func refineText(_ params: RefineTextRequest) async throws -> RefineTextResponse {
        try await encatch.refineText(params)
    }
}

/// A response value handed to the queue (the SDK takes `Any?`, as from JSON).
private struct SendableValue: @unchecked Sendable {
    let value: Any?
    init(_ value: Any?) { self.value = value }
}

/// Unsubscribes from the SDK's events when the context goes away (a non-isolated holder, so a
/// main-actor class can clean up in `deinit`).
private final class Subscription: @unchecked Sendable {
    var cancel: (() -> Void)?
    deinit { cancel?() }
}
