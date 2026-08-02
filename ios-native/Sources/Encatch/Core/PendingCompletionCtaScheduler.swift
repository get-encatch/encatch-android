import Foundation

/// Schedules `exit_form` completion CTAs after `form:complete`, mirroring `pendingCompletionCta.ts`.
/// The UI layer calls `schedule`/`cancel`; actual URL opening (`SFSafariViewController` / system
/// browser) is platform UI, invoked here via `RedirectOpener`.
public protocol RedirectOpener: Sendable {
    func openInternal(url: String) async
}

public final class PendingCompletionCtaScheduler: @unchecked Sendable {
    static let redirectInternalAfterCloseDelayMs: Int64 = 400
    static let ctaAfterCloseDelayMs: Int64 = 50

    private let redirectOpener: RedirectOpener
    private let emitEvent: @Sendable (EventType, EventPayload) -> Void
    private let openExternal: @Sendable (String) async -> Void

    private let lock = NSLock()
    private var pendingTasks: [String: Task<Void, Never>] = [:]

    public init(
        redirectOpener: RedirectOpener,
        emitEvent: @escaping @Sendable (EventType, EventPayload) -> Void,
        openExternal: @escaping @Sendable (String) async -> Void
    ) {
        self.redirectOpener = redirectOpener
        self.emitEvent = emitEvent
        self.openExternal = openExternal
    }

    private func removePendingTask(formId: String) {
        lock.lock()
        pendingTasks.removeValue(forKey: formId)
        lock.unlock()
    }

    private func effectiveDelayMs(_ pending: PendingCompletionCta) -> Int64 {
        if pending.autoTriggerDelayMs > 0 { return pending.autoTriggerDelayMs }
        if pending.action == CtaAction.redirectInternal { return Self.redirectInternalAfterCloseDelayMs }
        if pending.action != CtaAction.dismiss { return Self.ctaAfterCloseDelayMs }
        return 0
    }

    public func cancel(formId: String? = nil) {
        lock.lock()
        defer { lock.unlock() }
        if let formId {
            pendingTasks.removeValue(forKey: formId)?.cancel()
            return
        }
        pendingTasks.values.forEach { $0.cancel() }
        pendingTasks.removeAll()
    }

    public func schedule(formId: String, pending: PendingCompletionCta) {
        cancel(formId: formId)
        let delayMs = effectiveDelayMs(pending)
        if delayMs <= 0 {
            let task = Task { [weak self] in
                guard let self else { return }
                await self.execute(formId: formId, pending: pending)
            }
            lock.lock()
            pendingTasks[formId] = task
            lock.unlock()
            return
        }
        let task = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            if Task.isCancelled { return }
            guard let self else { return }
            self.removePendingTask(formId: formId)
            await self.execute(formId: formId, pending: pending)
        }
        lock.lock()
        pendingTasks[formId] = task
        lock.unlock()
    }

    private func execute(formId: String, pending: PendingCompletionCta) async {
        var data: [String: JSONValue] = [
            "action": .string(pending.action),
            "surface": .string(pending.surface),
            "trigger": .string(pending.trigger),
        ]
        if let url = pending.url { data["url"] = .string(url) }
        if let route = pending.route { data["route"] = .string(route) }

        switch pending.action {
        case CtaAction.dismiss:
            return
        case CtaAction.appNavigate:
            emitEvent(.formCtaTriggered, EventPayload(formId: formId, timestamp: currentTimeMillis(), data: data))
        case CtaAction.redirectInternal:
            guard let url = pending.url else { return }
            await redirectOpener.openInternal(url: url)
            emitEvent(.formCtaTriggered, EventPayload(formId: formId, timestamp: currentTimeMillis(), data: data))
        case CtaAction.redirectExternal:
            guard let url = pending.url else { return }
            await openExternal(url)
            emitEvent(.formCtaTriggered, EventPayload(formId: formId, timestamp: currentTimeMillis(), data: data))
        default:
            return
        }
    }
}

/// Defensive parse of the wire-format `pendingCompletionCta` payload, mirrors `parsePendingCompletionCta`.
func parsePendingCompletionCta(_ json: JSONValue?) -> PendingCompletionCta? {
    guard case .object(let object) = json else { return nil }
    guard case .string(let action)? = object["action"] else { return nil }

    var autoTriggerDelayMs: Int64 = 0
    if case .number(let delayRaw)? = object["autoTriggerDelayMs"], delayRaw >= 0 {
        autoTriggerDelayMs = Int64(delayRaw)
    }

    var surfaceValue: String?
    if case .string(let surface)? = object["surface"] {
        surfaceValue = surface
    }

    var urlValue: String?
    if case .string(let url)? = object["url"] {
        urlValue = url
    }

    var routeValue: String?
    if case .string(let route)? = object["route"] {
        routeValue = route
    }

    return PendingCompletionCta(
        action: action,
        url: urlValue,
        route: routeValue,
        surface: surfaceValue == "link" ? "link" : "inApp",
        trigger: "auto",
        autoTriggerDelayMs: autoTriggerDelayMs
    )
}
