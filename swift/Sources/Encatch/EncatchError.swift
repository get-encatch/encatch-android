import EncatchCore
import Foundation

/// Typed error wrapper. Kotlin/Native bridges thrown exceptions to `NSError` across the
/// suspend-function/completion-handler (and therefore `async throws`) boundary, collapsing
/// specific Kotlin exception types into a generic error unless unwrapped explicitly. Kotlin/Native
/// stashes the original Kotlin exception object in `NSError.userInfo["KotlinException"]`, which we
/// use here to recover `EncatchApiException`'s status/endpoint/responseBody and distinguish
/// "SDK not initialized" from other failures, instead of every caller pattern-matching on
/// `localizedDescription` strings.
public enum EncatchError: Error, LocalizedError, Sendable {
    case notInitialized
    case apiError(endpoint: String, status: Int, responseBody: String)
    case other(message: String)

    public var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "Encatch SDK not initialized — call Encatch.shared.initialize(apiKey:config:) first."
        case let .apiError(endpoint, status, responseBody):
            return "[Encatch API] \(endpoint) failed with status \(status): \(responseBody)"
        case let .other(message):
            return message
        }
    }

    public init(_ error: Error) {
        let nsError = error as NSError
        guard let kotlinException = nsError.userInfo["KotlinException"] else {
            self = .other(message: nsError.localizedDescription)
            return
        }
        if kotlinException is EncatchNotInitializedException {
            self = .notInitialized
            return
        }
        if let apiException = kotlinException as? EncatchApiException {
            self = .apiError(endpoint: apiException.endpoint, status: Int(apiException.status), responseBody: apiException.responseBody)
            return
        }
        self = .other(message: nsError.localizedDescription)
    }
}
