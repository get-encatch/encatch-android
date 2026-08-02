import Foundation

/// Typed error wrapper for the Encatch SDK's public API. In the old Kotlin/Native-bridged
/// package, this unwrapped `NSError.userInfo["KotlinException"]` to recover the original thrown
/// Kotlin exception type — that was purely a Kotlin/Native interop artifact. Now that
/// `EncatchNotInitializedException`/`EncatchApiException` (see `Core/ApiClient.swift`) are genuine
/// Swift `Error` types thrown directly by `Core/Encatch.swift`, no unwrapping is needed: this type
/// just pattern-matches on them directly.
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
        switch error {
        case is EncatchNotInitializedException:
            self = .notInitialized
        case let apiException as EncatchApiException:
            self = .apiError(endpoint: apiException.endpoint, status: apiException.status, responseBody: apiException.responseBody)
        case let convertible as CustomStringConvertible:
            self = .other(message: convertible.description)
        default:
            self = .other(message: String(describing: error))
        }
    }
}
