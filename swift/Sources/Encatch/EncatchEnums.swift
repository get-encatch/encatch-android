import EncatchCore

/// Real Swift enums standing in for the Kotlin/Native-bridged class hierarchies (`Theme`,
/// `ResetMode`, `TriggerType`, `EventType`) — those come through as ObjC class hierarchies rather
/// than native Swift `enum`s, which loses compiler-enforced exhaustiveness in `switch` statements.
/// These wrappers restore that, and convert to/from the underlying bridged type at the boundary.

public enum EncatchTheme: String, Sendable {
    case light, dark, system

    var kotlin: Theme {
        switch self {
        case .light: return Theme.light
        case .dark: return Theme.dark
        case .system: return Theme.system
        }
    }

    init(kotlin: Theme) {
        switch kotlin {
        case Theme.dark: self = .dark
        case Theme.system: self = .system
        default: self = .light
        }
    }
}

public enum EncatchResetMode: String, Sendable {
    /// Clear form data every time showForm is called (default).
    case always
    /// Clear form data only if the form was previously completed.
    case onComplete
    /// Never clear form data — preserve the user's previous answers.
    case never

    var kotlin: ResetMode {
        switch self {
        case .always: return ResetMode.always
        case .onComplete: return ResetMode.onComplete
        case .never: return ResetMode.never
        }
    }

    init(kotlin: ResetMode) {
        switch kotlin {
        case ResetMode.onComplete: self = .onComplete
        case ResetMode.never: self = .never
        default: self = .always
        }
    }
}

public enum EncatchTriggerType: String, Sendable {
    case automatic, manual

    var kotlin: TriggerType {
        self == .automatic ? TriggerType.automatic : TriggerType.manual
    }

    init(kotlin: TriggerType) {
        self = kotlin == TriggerType.automatic ? .automatic : .manual
    }
}

public enum EncatchEventType: String, Sendable, CaseIterable {
    case formShow
    case formStarted
    case formSubmit
    case formComplete
    case formClose
    case formDismissed
    case formError
    case formSectionChange
    case formAnswered
    case formRemindMeLater
    case formCtaTriggered

    /// Unrecognized event types (future additions on the Kotlin side) surface here rather than
    /// crashing — check this case explicitly so new event types don't silently vanish.
    case unknown

    init(kotlin: EventType) {
        if kotlin == EventType.formShow { self = .formShow }
        else if kotlin == EventType.formStarted { self = .formStarted }
        else if kotlin == EventType.formSubmit { self = .formSubmit }
        else if kotlin == EventType.formComplete { self = .formComplete }
        else if kotlin == EventType.formClose { self = .formClose }
        else if kotlin == EventType.formDismissed { self = .formDismissed }
        else if kotlin == EventType.formError { self = .formError }
        else if kotlin == EventType.formSectionChange { self = .formSectionChange }
        else if kotlin == EventType.formAnswered { self = .formAnswered }
        else if kotlin == EventType.formRemindMeLater { self = .formRemindMeLater }
        else if kotlin == EventType.formCtaTriggered { self = .formCtaTriggered }
        else { self = .unknown }
    }
}
