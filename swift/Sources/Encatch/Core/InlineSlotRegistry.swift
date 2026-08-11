import Foundation

/// Inline slot registry, mirroring `form-presentation-registry.ts`.
///
/// Maintains an ordered list of mounted inline slots (e.g. `EncatchInlineFormView`s). When
/// `showForm` fires, `resolvePresentationTarget` determines whether it should render inline
/// (matching slot found) or fall through to the modal form dialog.
///
/// Routing rules:
///  1. Exact match — first slot whose formId matches the payload ids wins.
///  2. Wildcard    — first slot with no formId catches anything not exact-matched.
///  3. Modal       — no inline slot registered or none match.
public struct InlineSlot: Sendable, Equatable {
    public var slotId: String
    public var formId: String?

    public init(slotId: String, formId: String? = nil) {
        self.slotId = slotId
        self.formId = formId
    }
}

public enum PresentationTarget: Sendable, Equatable {
    case inline(slotId: String)
    case modal
}

/// Class (not a plain namespace) since it holds mutable state — one shared instance, mirrors the
/// Kotlin `object`.
public final class InlineSlotRegistry: @unchecked Sendable {
    public static let shared = InlineSlotRegistry()

    private let lock = NSLock()
    private var slots: [InlineSlot] = []

    private init() {}

    /// Registers a new inline slot on view attach. Returns an opaque slotId to use with
    /// `unregisterInlineSlot`/`updateInlineSlot`. Registration order is preserved — first-registered
    /// wins for wildcard resolution.
    public func registerInlineSlot(formId: String? = nil) -> String {
        let slotId = uuidV7()
        lock.lock()
        slots.append(InlineSlot(slotId: slotId, formId: formId))
        lock.unlock()
        return slotId
    }

    /// Removes an inline slot on view detach.
    public func unregisterInlineSlot(_ slotId: String) {
        lock.lock()
        slots.removeAll { $0.slotId == slotId }
        lock.unlock()
    }

    /// Updates the formId of an existing slot without changing its registration order.
    public func updateInlineSlot(_ slotId: String, formId: String?) {
        lock.lock()
        if let index = slots.firstIndex(where: { $0.slotId == slotId }) {
            slots[index].formId = formId
        }
        lock.unlock()
    }

    /// Determines whether the given showForm payload should render inline or modal.
    ///
    /// ID matching checks the slot's formId against `formId` (the slug/uuid passed by the
    /// caller, or formConfigurationId) and `feedbackConfigurationId` (server-resolved id).
    /// Single pass: first exact match wins, then the first wildcard; else modal.
    public func resolvePresentationTarget(formId: String, feedbackConfigurationId: String?) -> PresentationTarget {
        var candidateIds = Set<String>()
        if !formId.isEmpty { candidateIds.insert(formId) }
        if let feedbackConfigurationId, !feedbackConfigurationId.isEmpty {
            candidateIds.insert(feedbackConfigurationId)
        }

        lock.lock()
        let snapshot = slots
        lock.unlock()

        var firstWildcard: InlineSlot?
        for slot in snapshot {
            if let slotFormId = slot.formId {
                if candidateIds.contains(slotFormId) {
                    return .inline(slotId: slot.slotId)
                }
            } else if firstWildcard == nil {
                firstWildcard = slot
            }
        }

        if let firstWildcard {
            return .inline(slotId: firstWildcard.slotId)
        }
        return .modal
    }

    /// Exposed for testing only — do not use in production code.
    public func slotsSnapshot() -> [InlineSlot] {
        lock.lock()
        defer { lock.unlock() }
        return slots
    }

    /// Exposed for testing only — clears the registry.
    public func clearSlots() {
        lock.lock()
        slots.removeAll()
        lock.unlock()
    }
}
