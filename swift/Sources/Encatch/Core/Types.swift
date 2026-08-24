import Foundation

/// Default theme for forms.
public enum Theme: String, Sendable {
    case light = "LIGHT"
    case dark = "DARK"
    case system = "SYSTEM"

    public var wireValue: String {
        switch self {
        case .light: return "light"
        case .dark: return "dark"
        case .system: return "system"
        }
    }

    public static func fromWire(_ value: String?) -> Theme {
        switch value {
        case "light": return .light
        case "dark": return .dark
        default: return .system
        }
    }
}

public enum TriggerType: String, Sendable {
    case automatic = "AUTOMATIC"
    case manual = "MANUAL"

    public var wireValue: String {
        switch self {
        case .automatic: return "automatic"
        case .manual: return "manual"
        }
    }
}

/// Reset mode for form data when showing a form.
/// - always: Clear form data every time showForm is called (default)
/// - onComplete: Clear form data only if form was previously completed
/// - never: Never clear form data (preserve user's previous answers)
public enum ResetMode: String, Sendable {
    case always = "ALWAYS"
    case onComplete = "ON_COMPLETE"
    case never = "NEVER"

    public var wireValue: String {
        switch self {
        case .always: return "always"
        case .onComplete: return "on-complete"
        case .never: return "never"
        }
    }

    public static func fromWire(_ value: String?) -> ResetMode {
        switch value {
        case "on-complete": return .onComplete
        case "never": return .never
        default: return .always
        }
    }
}

/// Arbitrary context value attached to a form submission. Dates are serialized to ISO strings before sending.
public enum ContextValue: Sendable {
    case string(String)
    case number(Double)
    case boolean(Bool)
    /// Epoch millis; serialized to an ISO-8601 string on the wire, matching the RN SDK's Date handling.
    case date(epochMillis: Int64)
}

public struct ShowFormOptions: Sendable {
    public var reset: ResetMode
    public var context: [String: ContextValue]

    public init(reset: ResetMode = .always, context: [String: ContextValue] = [:]) {
        self.reset = reset
        self.context = context
    }
}

public struct UserTraits: Sendable {
    public var set: [String: JSONValue]?
    public var setOnce: [String: JSONValue]?
    public var increment: [String: Double]?
    public var decrement: [String: Double]?
    public var unset: [String]?

    public init(
        set: [String: JSONValue]? = nil,
        setOnce: [String: JSONValue]? = nil,
        increment: [String: Double]? = nil,
        decrement: [String: Double]? = nil,
        unset: [String]? = nil
    ) {
        self.set = set
        self.setOnce = setOnce
        self.increment = increment
        self.decrement = decrement
        self.unset = unset
    }
}

public struct SecureOptions: Sendable {
    public var signature: String
    public var generatedDateTimeInUtc: String?

    public init(signature: String, generatedDateTimeInUtc: String? = nil) {
        self.signature = signature
        self.generatedDateTimeInUtc = generatedDateTimeInUtc
    }
}

public struct IdentifyOptions: Sendable {
    public var locale: String?
    public var country: String?
    public var secure: SecureOptions?

    public init(locale: String? = nil, country: String? = nil, secure: SecureOptions? = nil) {
        self.locale = locale
        self.country = country
        self.secure = secure
    }
}

public struct StartSessionOptions: Sendable {
    public var skipImmediatePing: Bool
    public var skipImmediateTrackScreen: Bool

    public init(skipImmediatePing: Bool = false, skipImmediateTrackScreen: Bool = false) {
        self.skipImmediatePing = skipImmediatePing
        self.skipImmediateTrackScreen = skipImmediateTrackScreen
    }
}

/// Event types emitted by the SDK via `Encatch.on`.
public enum EventType: String, Sendable, CaseIterable {
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

    public var wireValue: String {
        switch self {
        case .formShow: return "form:show"
        case .formStarted: return "form:started"
        case .formSubmit: return "form:submit"
        case .formComplete: return "form:complete"
        case .formClose: return "form:close"
        case .formDismissed: return "form:dismissed"
        case .formError: return "form:error"
        case .formSectionChange: return "form:section:change"
        case .formAnswered: return "form:answered"
        case .formRemindMeLater: return "form:remindmelater"
        case .formCtaTriggered: return "form:ctaTriggered"
        }
    }

    public static func fromWire(_ value: String) -> EventType? {
        allCases.first { $0.wireValue == value }
    }
}

public struct EventPayload: Sendable {
    public var formId: String?
    public var timestamp: Int64
    public var data: [String: JSONValue]?

    public init(formId: String? = nil, timestamp: Int64, data: [String: JSONValue]? = nil) {
        self.formId = formId
        self.timestamp = timestamp
        self.data = data
    }
}

public typealias EventCallback = (EventType, EventPayload) -> Void

/// Form title/description metadata returned by fetch-feedback APIs — mirrors
/// `@encatch/schema`'s `formConfigurationResponseSchema` (`fetch-feedback-schema.ts`).
public struct FormConfigurationResponse: Sendable, Equatable {
    public var formTitle: String
    public var formDescription: String
    /// Server-enriched total respondent count, used for the welcome badge.
    public var respondentsCount: Int?

    public init(formTitle: String = "", formDescription: String = "", respondentsCount: Int? = nil) {
        self.formTitle = formTitle
        self.formDescription = formDescription
        self.respondentsCount = respondentsCount
    }

    /// Defensive parse — unknown/missing fields fall back to defaults, never throws.
    public static func fromJson(_ json: [String: JSONValue]?) -> FormConfigurationResponse? {
        guard let json else { return nil }
        return FormConfigurationResponse(
            formTitle: json["formTitle"]?.asString ?? "",
            formDescription: json["formDescription"]?.asString ?? "",
            respondentsCount: json["respondentsCount"]?.asDouble.map { Int($0) }
        )
    }
}

/// A single logic-jump rule evaluated during form navigation — mirrors
/// `@encatch/schema`'s `logicJumpRuleSchema`. Kept high-level: `jsonLogic` is the raw
/// JSON Logic expression, not modeled further.
public struct LogicJumpRule: Sendable, Equatable {
    public var jsonLogic: [String: JSONValue]
    public var targetQuestionId: String

    public init(jsonLogic: [String: JSONValue] = [:], targetQuestionId: String = "") {
        self.jsonLogic = jsonLogic
        self.targetQuestionId = targetQuestionId
    }

    public static func fromJson(_ json: [String: JSONValue]?) -> LogicJumpRule? {
        guard let json else { return nil }
        return LogicJumpRule(
            jsonLogic: json["jsonLogic"]?.asObject ?? [:],
            targetQuestionId: json["targetQuestionId"]?.asString ?? ""
        )
    }
}

/// Supported completion CTA actions on thank_you and exit_form screens.
public enum CompletionCtaAction: String, Sendable, CaseIterable {
    case dismiss
    case appNavigate
    case redirectInternal
    case redirectExternal

    public var wireValue: String {
        switch self {
        case .dismiss: return "dismiss"
        case .appNavigate: return "app_navigate"
        case .redirectInternal: return "redirect_internal"
        case .redirectExternal: return "redirect_external"
        }
    }

    public static func fromWire(_ value: String?) -> CompletionCtaAction? {
        allCases.first { $0.wireValue == value }
    }
}

/// Per-surface completion CTA action (in-app vs shareable link) — mirrors
/// `@encatch/schema`'s `platformCompletionCtaSchema`. This is the *static config* shape on
/// thank_you/exit_form questions; the runtime wire payload the web engine hands back after
/// submit is `PendingCompletionCta`.
public struct PlatformCompletionCta: Sendable, Equatable {
    public var action: CompletionCtaAction
    /// App-specific route for `CompletionCtaAction.appNavigate`.
    public var route: String?
    /// Target URL for the redirect actions.
    public var url: String?

    public init(action: CompletionCtaAction, route: String? = nil, url: String? = nil) {
        self.action = action
        self.route = route
        self.url = url
    }

    /// Defensive parse — an unknown action falls back to `.dismiss`.
    public static func fromJson(_ json: [String: JSONValue]?) -> PlatformCompletionCta? {
        guard let json else { return nil }
        return PlatformCompletionCta(
            action: CompletionCtaAction.fromWire(json["action"]?.asString) ?? .dismiss,
            route: json["route"]?.asString,
            url: json["url"]?.asString
        )
    }
}

/// Optional secondary button on thank_you completion CTAs; label-only configs default to dismiss.
public struct CompletionCtaSecondary: Sendable, Equatable {
    public var label: String
    public var inApp: PlatformCompletionCta?
    public var link: PlatformCompletionCta?

    public init(label: String, inApp: PlatformCompletionCta? = nil, link: PlatformCompletionCta? = nil) {
        self.label = label
        self.inApp = inApp
        self.link = link
    }

    public static func fromJson(_ json: [String: JSONValue]?) -> CompletionCtaSecondary? {
        guard let json else { return nil }
        return CompletionCtaSecondary(
            label: json["label"]?.asString ?? "",
            inApp: PlatformCompletionCta.fromJson(json["inApp"]?.asObject),
            link: PlatformCompletionCta.fromJson(json["link"]?.asObject)
        )
    }
}

/// Completion CTA configuration for thank_you and exit_form questions — mirrors
/// `@encatch/schema`'s `completionCtaSchema` (`completion-cta-schema.ts`).
public struct CompletionCta: Sendable, Equatable {
    public var label: String?
    /// When set, auto-fires the primary action after this many milliseconds.
    public var autoTriggerDelayMs: Int64?
    public var inApp: PlatformCompletionCta?
    public var link: PlatformCompletionCta?
    public var secondary: CompletionCtaSecondary?

    public init(
        label: String? = nil,
        autoTriggerDelayMs: Int64? = nil,
        inApp: PlatformCompletionCta? = nil,
        link: PlatformCompletionCta? = nil,
        secondary: CompletionCtaSecondary? = nil
    ) {
        self.label = label
        self.autoTriggerDelayMs = autoTriggerDelayMs
        self.inApp = inApp
        self.link = link
        self.secondary = secondary
    }

    public static func fromJson(_ json: [String: JSONValue]?) -> CompletionCta? {
        guard let json else { return nil }
        return CompletionCta(
            label: json["label"]?.asString,
            autoTriggerDelayMs: json["autoTriggerDelayMs"]?.asDouble.map { Int64($0) },
            inApp: PlatformCompletionCta.fromJson(json["inApp"]?.asObject),
            link: PlatformCompletionCta.fromJson(json["link"]?.asObject),
            secondary: CompletionCtaSecondary.fromJson(json["secondary"]?.asObject)
        )
    }
}

/// Wire-format payload for exit_form completion CTAs deferred to the native SDK timer.
public struct PendingCompletionCta: Codable, Sendable {
    /// "dismiss" | "app_navigate" | "redirect_internal" | "redirect_external"
    public var action: String
    public var url: String?
    public var route: String?
    /// "inApp" | "link"
    public var surface: String
    public var trigger: String
    public var autoTriggerDelayMs: Int64

    public init(
        action: String,
        url: String? = nil,
        route: String? = nil,
        surface: String = "inApp",
        trigger: String = "auto",
        autoTriggerDelayMs: Int64 = 0
    ) {
        self.action = action
        self.url = url
        self.route = route
        self.surface = surface
        self.trigger = trigger
        self.autoTriggerDelayMs = autoTriggerDelayMs
    }
}

public struct ShowFormInterceptorPayload: Sendable {
    public var formId: String
    public var formConfig: ShowFormResponse
    public var resetMode: ResetMode
    public var triggerType: TriggerType
    public var prefillResponses: [String: JSONValue]
    public var locale: String?
    public var theme: Theme?
    public var context: [String: JSONValue]?

    public init(
        formId: String,
        formConfig: ShowFormResponse,
        resetMode: ResetMode,
        triggerType: TriggerType,
        prefillResponses: [String: JSONValue],
        locale: String? = nil,
        theme: Theme? = nil,
        context: [String: JSONValue]? = nil
    ) {
        self.formId = formId
        self.formConfig = formConfig
        self.resetMode = resetMode
        self.triggerType = triggerType
        self.prefillResponses = prefillResponses
        self.locale = locale
        self.theme = theme
        self.context = context
    }
}

public struct ApiDeviceInfo: Sendable {
    public var deviceOs: String?
    public var deviceVersion: String?
    public var deviceOsVersion: String?
    public var deviceType: String?
    /// "mobile" | "tablet" | "desktop"
    public var deviceSize: String?
    public var sdkVersion: String?
    public var appVersion: String?
    public var app: String?
    public var deviceLanguage: String?
    public var userLanguage: String?
    public var countryCode: String?
    public var preferredTheme: String?
    public var timezone: String?
    public var urlOrScreenName: String?

    public init(
        deviceOs: String? = nil,
        deviceVersion: String? = nil,
        deviceOsVersion: String? = nil,
        deviceType: String? = nil,
        deviceSize: String? = nil,
        sdkVersion: String? = nil,
        appVersion: String? = nil,
        app: String? = nil,
        deviceLanguage: String? = nil,
        userLanguage: String? = nil,
        countryCode: String? = nil,
        preferredTheme: String? = nil,
        timezone: String? = nil,
        urlOrScreenName: String? = nil
    ) {
        self.deviceOs = deviceOs
        self.deviceVersion = deviceVersion
        self.deviceOsVersion = deviceOsVersion
        self.deviceType = deviceType
        self.deviceSize = deviceSize
        self.sdkVersion = sdkVersion
        self.appVersion = appVersion
        self.app = app
        self.deviceLanguage = deviceLanguage
        self.userLanguage = userLanguage
        self.countryCode = countryCode
        self.preferredTheme = preferredTheme
        self.timezone = timezone
        self.urlOrScreenName = urlOrScreenName
    }
}

public struct ShowFormResponse: Sendable {
    public var feedbackConfigurationId: String
    public var feedbackIdentifier: String?
    public var triggerType: TriggerType?
    public var formConfiguration: [String: JSONValue]?
    public var questionnaireFields: JSONValue?
    public var otherConfigurationProperties: JSONValue?
    public var appearanceProperties: JSONValue?
    public var partialResponseEnabled: Bool?
    public var contact: [String: JSONValue]?
    public var projectI18nFileUrl: String?
    public var pingAgainIn: Double?
    public var pingOnNextPageVisit: Bool?
    public var feedbackTransactions: String?

    public init(
        feedbackConfigurationId: String,
        feedbackIdentifier: String? = nil,
        triggerType: TriggerType? = nil,
        formConfiguration: [String: JSONValue]? = nil,
        questionnaireFields: JSONValue? = nil,
        otherConfigurationProperties: JSONValue? = nil,
        appearanceProperties: JSONValue? = nil,
        partialResponseEnabled: Bool? = nil,
        contact: [String: JSONValue]? = nil,
        projectI18nFileUrl: String? = nil,
        pingAgainIn: Double? = nil,
        pingOnNextPageVisit: Bool? = nil,
        feedbackTransactions: String? = nil
    ) {
        self.feedbackConfigurationId = feedbackConfigurationId
        self.feedbackIdentifier = feedbackIdentifier
        self.triggerType = triggerType
        self.formConfiguration = formConfiguration
        self.questionnaireFields = questionnaireFields
        self.otherConfigurationProperties = otherConfigurationProperties
        self.appearanceProperties = appearanceProperties
        self.partialResponseEnabled = partialResponseEnabled
        self.contact = contact
        self.projectI18nFileUrl = projectI18nFileUrl
        self.pingAgainIn = pingAgainIn
        self.pingOnNextPageVisit = pingOnNextPageVisit
        self.feedbackTransactions = feedbackTransactions
    }

    /// Parses `formConfiguration` into the typed fetch-feedback shape, or `nil` when absent.
    public var typedFormConfiguration: FormConfigurationResponse? {
        FormConfigurationResponse.fromJson(formConfiguration)
    }
}

public struct RefineTextRequest: Sendable {
    public var questionId: String
    public var feedbackConfigurationId: String
    public var userText: String

    public init(questionId: String, feedbackConfigurationId: String, userText: String) {
        self.questionId = questionId
        self.feedbackConfigurationId = feedbackConfigurationId
        self.userText = userText
    }
}

public struct RefineTextResponse: Sendable {
    public var message: String?
    public var refinedText: String?
    public var status: Int?
    public var error: String?

    public init(message: String? = nil, refinedText: String? = nil, status: Int? = nil, error: String? = nil) {
        self.message = message
        self.refinedText = refinedText
        self.status = status
        self.error = error
    }
}

public struct QnaWithAiConversationTurn: Sendable {
    public var question: String
    public var answer: String

    public init(question: String, answer: String) {
        self.question = question
        self.answer = answer
    }
}

public struct QnaWithAiRequest: Sendable {
    public var feedbackConfigurationId: String
    public var questionId: String
    public var conversation: [QnaWithAiConversationTurn]

    public init(feedbackConfigurationId: String, questionId: String, conversation: [QnaWithAiConversationTurn] = []) {
        self.feedbackConfigurationId = feedbackConfigurationId
        self.questionId = questionId
        self.conversation = conversation
    }
}

public struct QnaWithAiResponse: Sendable {
    public var answer: String

    public init(answer: String) {
        self.answer = answer
    }
}

/// A local file reference to upload — either raw bytes or a content URI resolved by the host app.
public enum UploadFileSource: Sendable {
    case bytes(Data, mimeType: String? = nil)
    case contentUri(String, mimeType: String? = nil)
}

public struct UploadFileRequest: Sendable {
    public var feedbackConfigurationId: String
    public var questionId: String
    public var file: UploadFileSource
    public var fileName: String
    public var onProgress: (@Sendable (Int) -> Void)?

    public init(
        feedbackConfigurationId: String,
        questionId: String,
        file: UploadFileSource,
        fileName: String = "upload",
        onProgress: (@Sendable (Int) -> Void)? = nil
    ) {
        self.feedbackConfigurationId = feedbackConfigurationId
        self.questionId = questionId
        self.file = file
        self.fileName = fileName
        self.onProgress = onProgress
    }
}

public struct UploadFileResponse: Sendable {
    public var fileUrl: String

    public init(fileUrl: String) {
        self.fileUrl = fileUrl
    }
}

public enum QuestionType: String, Sendable, CaseIterable {
    case rating
    case singleChoice
    case nps
    case nestedSelection
    case multipleChoiceMultiple
    case shortAnswer
    case longText

    /// - Note: Deprecated — the `annotation` question type is no longer supported for new forms.
    ///   Kept for backward compatibility with existing configurations. (Doc-level only: a formal
    ///   `@available(*, deprecated)` would warn on the SDK's own exhaustive switches.)
    case annotation
    case welcome
    case thankYou
    case messagePanel
    case yesNo
    case ratingMatrix
    case matrixSingleChoice
    case matrixMultipleChoice
    case exitForm
    case consent
    case date
    case csat
    case opinionScale
    case ranking
    case pictureChoice
    case signature
    case fileUpload
    case email
    case number
    case website
    case phoneNumber
    case address
    case videoAudio
    case scheduler
    case qnaWithAi

    /// - Note: Deprecated — the `payments_upi` question type is slated for removal.
    ///   Kept for backward compatibility with existing configurations. (Doc-level only, see
    ///   `annotation`.)
    case paymentsUpi

    public var wireValue: String {
        switch self {
        case .rating: return "rating"
        case .singleChoice: return "single_choice"
        case .nps: return "nps"
        case .nestedSelection: return "nested_selection"
        case .multipleChoiceMultiple: return "multiple_choice_multiple"
        case .shortAnswer: return "short_answer"
        case .longText: return "long_text"
        case .annotation: return "annotation"
        case .welcome: return "welcome"
        case .thankYou: return "thank_you"
        case .messagePanel: return "message_panel"
        case .yesNo: return "yes_no"
        case .ratingMatrix: return "rating_matrix"
        case .matrixSingleChoice: return "matrix_single_choice"
        case .matrixMultipleChoice: return "matrix_multiple_choice"
        case .exitForm: return "exit_form"
        case .consent: return "consent"
        case .date: return "date"
        case .csat: return "csat"
        case .opinionScale: return "opinion_scale"
        case .ranking: return "ranking"
        case .pictureChoice: return "picture_choice"
        case .signature: return "signature"
        case .fileUpload: return "file_upload"
        case .email: return "email"
        case .number: return "number"
        case .website: return "website"
        case .phoneNumber: return "phone_number"
        case .address: return "address"
        case .videoAudio: return "video_audio"
        case .scheduler: return "scheduler"
        case .qnaWithAi: return "qna_with_ai"
        case .paymentsUpi: return "payments_upi"
        }
    }

    public static func fromWire(_ value: String) -> QuestionType? {
        allCases.first { $0.wireValue == value }
    }
}
