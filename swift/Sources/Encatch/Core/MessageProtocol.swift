import Foundation

/// Messages emitted by the hosted form page (WebView) to the native SDK.
public enum FormMessageType: String, Sendable, CaseIterable {
    case ready
    case submit
    case complete
    case close
    case error
    case resize
    case layout
    case closeButton
    case themeData
    case refineTextRequest
    case started
    case answered
    case sectionChange
    case show
    case readyToDismiss
    case uploadFileRequest
    case qnaWithAiRequest
    case remindMeLater
    case ctaTriggered

    public var wireValue: String {
        switch self {
        case .ready: return "form:ready"
        case .submit: return "form:submit"
        case .complete: return "form:complete"
        case .close: return "form:close"
        case .error: return "form:error"
        case .resize: return "form:resize"
        case .layout: return "form:layout"
        case .closeButton: return "form:closeButton"
        case .themeData: return "form:themeData"
        case .refineTextRequest: return "form:refineTextRequest"
        case .started: return "form:started"
        case .answered: return "form:answered"
        case .sectionChange: return "form:section:change"
        case .show: return "form:show"
        case .readyToDismiss: return "form:readyToDismiss"
        case .uploadFileRequest: return "form:uploadFileRequest"
        case .qnaWithAiRequest: return "form:qnaWithAiRequest"
        case .remindMeLater: return "form:remindmelater"
        case .ctaTriggered: return "form:ctaTriggered"
        }
    }

    public static func fromWire(_ value: String) -> FormMessageType? {
        allCases.first { $0.wireValue == value }
    }
}

/// Envelope for a message received from the WebView, parsed via `JSONDecoder`.
public struct FormMessage: Codable, Sendable {
    public var type: String
    public var formId: String?
    public var data: JSONValue?

    public var messageType: FormMessageType? { FormMessageType.fromWire(type) }

    public init(type: String, formId: String? = nil, data: JSONValue? = nil) {
        self.type = type
        self.formId = formId
        self.data = data
    }
}

/// Messages sent from the native SDK to the hosted form page (WebView), via evaluateJavaScript.
public enum SDKMessageType: String, Sendable {
    case formConfig
    case theme
    case locale
    case resetData
    case prefillResponses
    case refineTextResponse
    case submitPartialBeforeDismiss
    case uploadFileResponse
    case uploadFileProgress
    case qnaWithAiResponse
    case qnaWithAiChunk
    case qnaWithAiDone

    public var wireValue: String {
        switch self {
        case .formConfig: return "sdk:formConfig"
        case .theme: return "sdk:theme"
        case .locale: return "sdk:locale"
        case .resetData: return "sdk:resetData"
        case .prefillResponses: return "sdk:prefillResponses"
        case .refineTextResponse: return "sdk:refineTextResponse"
        case .submitPartialBeforeDismiss: return "sdk:submitPartialBeforeDismiss"
        case .uploadFileResponse: return "sdk:uploadFileResponse"
        case .uploadFileProgress: return "sdk:uploadFileProgress"
        case .qnaWithAiResponse: return "sdk:qnaWithAiResponse"
        case .qnaWithAiChunk: return "sdk:qnaWithAiChunk"
        case .qnaWithAiDone: return "sdk:qnaWithAiDone"
        }
    }
}

/// Envelope for a message sent to the WebView; `dataJson` is a pre-serialized JSON object string, or nil.
public struct SDKMessage: Sendable {
    public var type: SDKMessageType
    public var dataJson: String?

    public init(type: SDKMessageType, dataJson: String? = nil) {
        self.type = type
        self.dataJson = dataJson
    }
}

/// Wire-format for a deferred exit_form CTA action, e.g. `redirect_internal`/`redirect_external`/`app_navigate`.
public enum CtaAction {
    public static let dismiss = "dismiss"
    public static let appNavigate = "app_navigate"
    public static let redirectInternal = "redirect_internal"
    public static let redirectExternal = "redirect_external"
}
