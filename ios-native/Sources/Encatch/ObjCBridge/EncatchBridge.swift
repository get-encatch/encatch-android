import Foundation
#if canImport(UIKit)
import UIKit
#endif

// MARK: - Why this file exists
//
// Kotlin/Native's cinterop tool only understands compiled Objective-C headers (via clang) — it
// cannot consume Swift modules directly, and it definitely can't consume `async`/`throws` Swift
// APIs. `compose-sample` and `kmp-sample` compile Kotlin/Native binaries for their iOS targets and
// need to drive this pure-Swift `Encatch` SDK (see `Core/Encatch.swift`) from Kotlin code. The new
// `:kmp-sdk` module (a separate follow-up) will consume this same facade to provide a full-parity
// `expect object Encatch` on iOS.
//
// This file is now a full-parity `@objc`-compatible facade over every public member of
// `Core/Encatch.swift` — see that file for the canonical behavior/semantics of each method; this
// file only concerns itself with the ObjC-compatibility translation at the boundary.
//
// Design constraints for `@objc` compatibility:
//  - Classes must inherit `NSObject`; no Swift-only enums with associated values, no `struct`s, no
//    generics in the exposed surface.
//  - Structured params get small `@objc` mirror classes (`EncatchBridgeConfig`,
//    `EncatchBridgeUserTraits`, etc.) instead of the Swift structs `Encatch.swift` actually takes.
//  - `async`/`throws` methods become completion-handler-based, wrapping a `Task { do { try await
//    ... } catch { ... } }` internally. Completions run on an arbitrary thread — callers needing
//    main-thread delivery hop themselves, same as any other async bridge.
//  - Swift closures aren't `Equatable`, so `on`/`off` can't be ported as a pair the way Kotlin's
//    `Encatch.on(callback)` / `Encatch.off(callback)` are (reference-equality of a function value
//    isn't a portable concept here). `Core/Emitter.swift` already made this same call for the pure
//    Swift API (`on` returns an unsubscribe closure, there is no `off(listener)` overload); this
//    bridge mirrors that: `onEvent(_:)` returns a `() -> Void` unsubscribe closure (bridged to an
//    Objective-C block, which Kotlin/Native cinterop consumes as a callable `() -> Unit`), and
//    there is no separate `off` entry point.
//  - Tradeoffs for the two "hardest" methods, `submitForm` and `refineText`/`uploadFile`:
//     - `submitForm`'s `SubmitFormRequest` (see `Core/Answer.swift`) is a deeply nested `Codable`
//       tree (`FormDetails` -> `FormResponse` -> `[QuestionResponse]` -> `Answer` -> several more
//       nested enums/structs). Hand-writing `@objc` mirror classes for that whole tree would be a
//       lot of boilerplate that has to be kept in lockstep with `Answer.swift` by hand. Instead,
//       `submitForm` takes the request pre-encoded as a JSON string on the wire format
//       `SubmitFormRequest`'s own `Codable` conformance already produces/consumes (the same shape
//       `JSONValue.toJSONString()`/`JSONDecoder` round-trip through elsewhere in this codebase) and
//       `JSONDecoder`s it back into a real `SubmitFormRequest` inside the bridge. The Kotlin caller
//       is responsible for building that JSON (trivial via `kotlinx.serialization` on a mirrored
//       Kotlin data class, or a plain string template for simple cases).
//     - `refineText`'s `RefineTextRequest` and `uploadFile`'s `UploadFileRequest`, by contrast, are
//       *flat* (three strings; a few strings + raw bytes + an optional progress callback) — no
//       nested `Codable` payload. For those, plain `@objc` parameters are simpler and safer than
//       JSON-string round-tripping, so this file uses that instead. `uploadFile` also only supports
//       `UploadFileSource.bytes` at the bridge boundary — `.contentUri` is Android-only in the
//       underlying Swift API too (see `EncatchUnsupportedOperationException` in `Core/Encatch.swift`).

// MARK: - Shared parsing helpers

/// Parses the same "light"/"dark"/"system" (case-insensitive) wire strings `EncatchBridgeConfig`
/// already accepts for its `theme` field. Unrecognized values (including `nil`) resolve to
/// `.system`, matching `Theme.fromWire`'s fallback behavior.
private func parseBridgeTheme(_ value: String?) -> Theme {
    switch value?.lowercased() {
    case "light": return .light
    case "dark": return .dark
    default: return .system
    }
}

/// Error surfaced to Kotlin/Native callers when a bridge method is handed malformed input it can't
/// convert into the corresponding Swift type (e.g. `submitForm`'s JSON string failing to decode).
private struct EncatchBridgeInputError: Error, CustomStringConvertible {
    let message: String
    var description: String { message }
}

/// `@objc` mirror of `ShowFormInterceptorPayload`, passed to `EncatchBridgeConfig.onBeforeShowForm`.
/// `formConfig` (the full form definition, `ShowFormResponse`) isn't mirrored as typed `@objc`
/// properties — it isn't `Codable` (unlike `SubmitFormRequest`) — but its `questionnaireFields`
/// (the structured question/section tree a host needs to hand-render its own form UI) is exposed
/// as a JSON string via `formConfigJSON`, the same pattern `prefillResponsesJSON`/`contextJSON`
/// already use one layer further in for `payload.prefillResponses`/`payload.context`.
@objc(EncatchBridgeShowFormInterceptorPayload)
public final class EncatchBridgeShowFormInterceptorPayload: NSObject {
    @objc public let formId: String
    /// One of "always" / "on-complete" / "never" (`ResetMode.wireValue`).
    @objc public let resetMode: String
    /// One of "automatic" / "manual" (`TriggerType.wireValue`).
    @objc public let triggerType: String
    @objc public let prefillResponsesJSON: String?
    @objc public let locale: String?
    /// One of "light" / "dark" / "system" (`Theme.wireValue`), or nil.
    @objc public let theme: String?
    @objc public let contextJSON: String?
    /// JSON encoding of `payload.formConfig.questionnaireFields`, or nil if the form config had none.
    @objc public let formConfigJSON: String?

    fileprivate init(_ payload: ShowFormInterceptorPayload) {
        self.formId = payload.formId
        self.resetMode = payload.resetMode.wireValue
        self.triggerType = payload.triggerType.wireValue
        self.prefillResponsesJSON = payload.prefillResponses.isEmpty ? nil : JSONValue.object(payload.prefillResponses).toJSONString()
        self.locale = payload.locale
        self.theme = payload.theme?.wireValue
        self.contextJSON = payload.context.map { JSONValue.object($0).toJSONString() }
        self.formConfigJSON = payload.formConfig.questionnaireFields?.toJSONString()
        super.init()
    }
}

/// Callback shape for `EncatchBridgeConfig.onBeforeShowForm`: the interceptor payload, plus a
/// completion block the host app calls whenever it has an answer (immediately, or after awaiting a
/// coroutine / a dialog tap / anything else — nothing here is on a deadline). Plain-callback shape
/// rather than an `async` closure, since `@objc` can't express Swift `async` directly: this is the
/// same completion-handler-passing-the-other-direction technique every other bridge method already
/// uses (Kotlin normally receives a completion block from Swift; here Kotlin instead *hands one to*
/// Swift, the same way `onEvent`'s callback closure already crosses this boundary from Kotlin).
public typealias EncatchBridgeInterceptorCallback = (EncatchBridgeShowFormInterceptorPayload, @escaping (Bool) -> Void) -> Void

/// `@objc`-compatible mirror of `EncatchConfig`, for Kotlin/Native cinterop callers that can't
/// construct the Swift struct directly. Unset fields fall back to `Encatch.initialize`'s own
/// defaults (see `EncatchConfig.init`).
@objc(EncatchBridgeConfig)
public final class EncatchBridgeConfig: NSObject {
    @objc public var apiBaseUrl: String?
    @objc public var webHost: String?
    @objc public var debugMode: Bool = false
    @objc public var isFullScreen: Bool = false
    @objc public var appVersion: String?
    /// One of "light" / "dark" / "system" (case-insensitive). Any other value (including nil)
    /// resolves to `.system`.
    @objc public var theme: String?
    /// See `EncatchBridgeInterceptorCallback`'s doc comment for why this isn't an `async` closure.
    @objc public var onBeforeShowForm: EncatchBridgeInterceptorCallback?

    @objc public override init() {
        super.init()
    }

    fileprivate func toEncatchConfig() -> EncatchConfig {
        let interceptor = onBeforeShowForm
        return EncatchConfig(
            apiBaseUrl: apiBaseUrl ?? DEFAULT_API_BASE_URL,
            webHost: webHost ?? DEFAULT_WEB_HOST,
            theme: parseBridgeTheme(theme),
            isFullScreen: isFullScreen,
            debugMode: debugMode,
            appVersion: appVersion,
            onBeforeShowForm: interceptor.map { handler in
                { (payload: ShowFormInterceptorPayload) async -> Bool in
                    await withCheckedContinuation { continuation in
                        handler(EncatchBridgeShowFormInterceptorPayload(payload)) { allow in
                            continuation.resume(returning: allow)
                        }
                    }
                }
            }
        )
    }
}

/// `@objc` mirror of `UserTraits` (see `Core/Types.swift`). `set`/`setOnce` are plain
/// `NSDictionary`s of JSON-safe values (String/NSNumber/nested dictionaries/arrays — anything
/// `JSONValue.from(any:)` understands); `increment`/`decrement` are `NSDictionary`s of
/// `String -> NSNumber`.
@objc(EncatchBridgeUserTraits)
public final class EncatchBridgeUserTraits: NSObject {
    @objc public var set: NSDictionary?
    @objc public var setOnce: NSDictionary?
    @objc public var increment: NSDictionary?
    @objc public var decrement: NSDictionary?
    @objc public var unset: [String]?

    @objc public override init() {
        super.init()
    }

    fileprivate func toUserTraits() -> UserTraits {
        func jsonDict(_ dict: NSDictionary?) -> [String: JSONValue]? {
            guard let dict = dict as? [String: Any] else { return nil }
            return dict.mapValues { JSONValue.from(any: $0) }
        }
        func numberDict(_ dict: NSDictionary?) -> [String: Double]? {
            guard let dict = dict as? [String: Any] else { return nil }
            return dict.compactMapValues { ($0 as? NSNumber)?.doubleValue }
        }
        return UserTraits(
            set: jsonDict(set),
            setOnce: jsonDict(setOnce),
            increment: numberDict(increment),
            decrement: numberDict(decrement),
            unset: unset
        )
    }
}

/// `@objc` mirror of `SecureOptions`.
@objc(EncatchBridgeSecureOptions)
public final class EncatchBridgeSecureOptions: NSObject {
    @objc public var signature: String
    @objc public var generatedDateTimeInUtc: String?

    @objc public init(signature: String) {
        self.signature = signature
        super.init()
    }

    fileprivate func toSecureOptions() -> SecureOptions {
        SecureOptions(signature: signature, generatedDateTimeInUtc: generatedDateTimeInUtc)
    }
}

/// `@objc` mirror of `IdentifyOptions`.
@objc(EncatchBridgeIdentifyOptions)
public final class EncatchBridgeIdentifyOptions: NSObject {
    @objc public var locale: String?
    @objc public var country: String?
    @objc public var secure: EncatchBridgeSecureOptions?

    @objc public override init() {
        super.init()
    }

    fileprivate func toIdentifyOptions() -> IdentifyOptions {
        IdentifyOptions(locale: locale, country: country, secure: secure?.toSecureOptions())
    }
}

/// `@objc` mirror of `StartSessionOptions`.
@objc(EncatchBridgeStartSessionOptions)
public final class EncatchBridgeStartSessionOptions: NSObject {
    @objc public var skipImmediatePing: Bool = false
    @objc public var skipImmediateTrackScreen: Bool = false

    @objc public override init() {
        super.init()
    }

    fileprivate func toStartSessionOptions() -> StartSessionOptions {
        StartSessionOptions(skipImmediatePing: skipImmediatePing, skipImmediateTrackScreen: skipImmediateTrackScreen)
    }
}

/// `@objc` mirror of `ShowFormOptions`. `reset` is one of `ResetMode.wireValue`'s wire strings
/// ("always" / "on-complete" / "never", case-insensitive; unrecognized/nil falls back to "always",
/// matching `ResetMode.fromWire`). `context` values may be `NSString`/`NSNumber` (including
/// booleans) — arbitrary nesting is not supported at this boundary (matches what `ContextValue`
/// itself allows: only string/number/boolean/date). Deviation from `ContextValue.date`: this
/// bridge has no reliable way to distinguish "this NSNumber represents an epoch-millis date" from
/// "this NSNumber is a plain number" once it's inside an untyped `NSDictionary`, so date context
/// values aren't specially supported here — callers who need the `date` wire behavior (ISO-8601
/// serialization) should pre-format the date as an ISO-8601 string and pass it as a plain string
/// value instead.
@objc(EncatchBridgeShowFormOptions)
public final class EncatchBridgeShowFormOptions: NSObject {
    @objc public var reset: String?
    @objc public var context: NSDictionary?

    @objc public override init() {
        super.init()
    }

    fileprivate func toShowFormOptions() -> ShowFormOptions {
        var ctx: [String: ContextValue] = [:]
        if let context = context as? [String: Any] {
            for (key, value) in context {
                if let string = value as? String {
                    ctx[key] = .string(string)
                } else if let number = value as? NSNumber {
                    if CFGetTypeID(number) == CFBooleanGetTypeID() {
                        ctx[key] = .boolean(number.boolValue)
                    } else {
                        ctx[key] = .number(number.doubleValue)
                    }
                }
            }
        }
        return ShowFormOptions(reset: ResetMode.fromWire(reset?.lowercased()), context: ctx)
    }
}

/// `@objc` mirror of `RefineTextResponse`.
@objc(EncatchBridgeRefineTextResponse)
public final class EncatchBridgeRefineTextResponse: NSObject {
    @objc public let message: String?
    @objc public let refinedText: String?
    /// `NSNumber` wrapping an `Int`, or `nil` if `RefineTextResponse.status` was `nil`.
    @objc public let status: NSNumber?
    @objc public let error: String?

    fileprivate init(_ response: RefineTextResponse) {
        self.message = response.message
        self.refinedText = response.refinedText
        self.status = response.status.map { NSNumber(value: $0) }
        self.error = response.error
        super.init()
    }
}

/// `@objc` mirror of `UploadFileResponse`.
@objc(EncatchBridgeUploadFileResponse)
public final class EncatchBridgeUploadFileResponse: NSObject {
    @objc public let fileUrl: String

    fileprivate init(_ response: UploadFileResponse) {
        self.fileUrl = response.fileUrl
        super.init()
    }
}

/// `@objc` mirror of `EventPayload`. `dataJSON` is the JSON-serialized form of `EventPayload.data`
/// (`nil` if `data` itself was `nil`) rather than a further `NSDictionary` mirror — callers that
/// need it can parse it with any JSON decoder on their side (Kotlin's `kotlinx.serialization`,
/// `JSONSerialization`, etc.), avoiding yet another dynamic-value bridging layer for a field that's
/// informational payload, not something the bridge itself needs to interpret.
@objc(EncatchBridgeEventPayload)
public final class EncatchBridgeEventPayload: NSObject {
    @objc public let formId: String?
    @objc public let timestamp: Int64
    @objc public let dataJSON: String?

    fileprivate init(_ payload: EventPayload) {
        self.formId = payload.formId
        self.timestamp = payload.timestamp
        self.dataJSON = payload.data.map { JSONValue.object($0).toJSONString() }
        super.init()
    }
}

/// Callback shape for `EncatchBridge.onEvent`: wire-format event type string (`EventType.wireValue`,
/// e.g. `"form:complete"`) plus the event payload.
public typealias EncatchBridgeEventCallback = (String, EncatchBridgeEventPayload) -> Void

public typealias EncatchBridgeNetworkLogCallback = (EncatchBridgeNetworkLogEntry) -> Void

/// ObjC mirror of `EncatchNetworkLogEntry` for `setOnNetworkLog`. `requestHeaders` crosses the
/// boundary as a JSON string (`requestHeadersJSON`), same pattern as `prefillResponsesJSON`.
@objc(EncatchBridgeNetworkLogEntry)
public final class EncatchBridgeNetworkLogEntry: NSObject {
    @objc public let timestampMs: Int64
    @objc public let method: String
    @objc public let endpoint: String
    @objc public let url: String
    @objc public let requestHeadersJSON: String
    @objc public let requestBody: String
    @objc public let status: Int
    @objc public let responseBody: String
    @objc public let durationMs: Int
    @objc public let error: String?

    init(_ entry: EncatchNetworkLogEntry) {
        self.timestampMs = Int64(entry.timestamp.timeIntervalSince1970 * 1000)
        self.method = entry.method
        self.endpoint = entry.endpoint
        self.url = entry.url
        self.requestHeadersJSON = JSONValue.object(entry.requestHeaders.mapValues { .string($0) }).toJSONString()
        self.requestBody = entry.requestBody
        self.status = entry.status
        self.responseBody = entry.responseBody
        self.durationMs = entry.durationMs
        self.error = entry.error
    }
}

/// The Kotlin/Native-facing entry point onto the pure-Swift `Encatch` singleton. See file-level
/// doc comment above for the full design rationale and the `submitForm`/`refineText`/`uploadFile`/
/// `on`-`off` tradeoffs specifically.
@objc(EncatchBridge)
public final class EncatchBridge: NSObject {
    @objc public static let shared = EncatchBridge()

    private override init() {
        super.init()
    }

    // ============================================================================
    // Initialisation
    // ============================================================================

    /// Mirrors `Encatch.shared.initialize(apiKey:config:)`.
    @objc public func initialize(
        apiKey: String,
        config: EncatchBridgeConfig?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await Encatch.shared.initialize(apiKey: apiKey, config: config?.toEncatchConfig())
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    @objc public var isInitialized: Bool { Encatch.shared.isInitialized }

    // ============================================================================
    // Identity
    // ============================================================================

    /// Mirrors `Encatch.shared.identifyUser(userName:traits:options:)`.
    @objc public func identifyUser(
        userName: String,
        traits: EncatchBridgeUserTraits?,
        options: EncatchBridgeIdentifyOptions?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await Encatch.shared.identifyUser(
                    userName: userName,
                    traits: traits?.toUserTraits(),
                    options: options?.toIdentifyOptions()
                )
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    @objc public func setLocale(_ locale: String) {
        Encatch.shared.setLocale(locale)
    }

    @objc public func setCountry(_ country: String) {
        Encatch.shared.setCountry(country)
    }

    /// `theme` is one of "light" / "dark" / "system" (case-insensitive); anything else resolves to
    /// `.system` (same parsing `EncatchBridgeConfig.theme` uses).
    @objc public func setTheme(_ theme: String) {
        Encatch.shared.setTheme(parseBridgeTheme(theme))
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    /// Mirrors `Encatch.shared.trackEvent(_:)`.
    @objc public func trackEvent(_ eventName: String, completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.trackEvent(eventName)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.trackFormEvent(_:_:)` — best-effort, never throws, so `completion`
    /// just signals "the underlying call finished" rather than carrying an error.
    @objc public func trackFormEvent(
        _ eventName: String,
        feedbackConfigurationId: String?,
        completion: @escaping () -> Void
    ) {
        Task {
            await Encatch.shared.trackFormEvent(eventName, feedbackConfigurationId)
            completion()
        }
    }

    /// Mirrors `Encatch.shared.trackScreen(_:)`.
    @objc public func trackScreen(_ screenName: String, completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.trackScreen(screenName)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    // ============================================================================
    // Form display
    // ============================================================================

    /// Mirrors `Encatch.shared.showForm(_:)` with default options (the shape the samples use
    /// today). Kept as its own overload (rather than requiring callers to always pass `options`)
    /// since it's already the established, shipped selector — see `showForm(_:options:completion:)`
    /// below for the full-parity version.
    @objc public func showForm(_ formId: String, completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.showForm(formId)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.showForm(_:options:)`.
    @objc public func showForm(
        _ formId: String,
        options: EncatchBridgeShowFormOptions?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await Encatch.shared.showForm(formId, options: options?.toShowFormOptions())
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.dismissForm(_:)`.
    @objc public func dismissForm(_ formConfigurationId: String?, completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.dismissForm(formConfigurationId)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    // ============================================================================
    // Form response helpers
    // ============================================================================

    /// Mirrors `Encatch.shared.addToResponse(questionId:value:)`. `value` is `AnyObject?` rather
    /// than `Any?` — `@objc` methods can't take plain `Any` parameters, only `AnyObject`-rooted
    /// ones, which covers every JSON-safe value a caller would realistically pass (`NSString`,
    /// `NSNumber`, `NSArray`, `NSDictionary`, `NSNull`).
    @objc public func addToResponse(questionId: String, value: AnyObject?) {
        Encatch.shared.addToResponse(questionId: questionId, value: value)
    }

    /// Mirrors `Encatch.shared.getPendingResponses()`. Returns an `NSDictionary` (via `JSONValue
    /// .toAny()`) rather than `[String: JSONValue]`, since `JSONValue` itself isn't `@objc`-representable.
    @objc public func getPendingResponses() -> NSDictionary {
        Encatch.shared.getPendingResponses().mapValues { $0.toAny() } as NSDictionary
    }

    @objc public func clearPendingResponses() {
        Encatch.shared.clearPendingResponses()
    }

    // ============================================================================
    // Submit form / refine text / upload
    // ============================================================================

    /// Mirrors `Encatch.shared.submitForm(_:)`. `requestJSON` must decode into `SubmitFormRequest`
    /// via `JSONDecoder` — i.e. the same wire shape `SubmitFormRequest`'s `Codable` conformance
    /// produces/consumes elsewhere in this SDK (see the file-level doc comment above for why this
    /// method takes JSON rather than a hand-built `@objc` mirror of the whole nested request tree).
    @objc public func submitForm(_ requestJSON: String, completion: @escaping (NSError?) -> Void) {
        guard
            let data = requestJSON.data(using: .utf8),
            let request = try? JSONDecoder().decode(SubmitFormRequest.self, from: data)
        else {
            completion(EncatchBridgeInputError(message: "submitForm: requestJSON did not decode as SubmitFormRequest") as NSError)
            return
        }
        Task {
            do {
                try await Encatch.shared.submitForm(request)
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.refineText(_:)`. Takes `RefineTextRequest`'s three fields directly
    /// (rather than JSON, unlike `submitForm`) since `RefineTextRequest` is flat with no nested
    /// payload — a plain `@objc` parameter list is simpler here.
    @objc public func refineText(
        questionId: String,
        feedbackConfigurationId: String,
        userText: String,
        completion: @escaping (EncatchBridgeRefineTextResponse?, NSError?) -> Void
    ) {
        Task {
            do {
                let response = try await Encatch.shared.refineText(RefineTextRequest(
                    questionId: questionId,
                    feedbackConfigurationId: feedbackConfigurationId,
                    userText: userText
                ))
                completion(EncatchBridgeRefineTextResponse(response), nil)
            } catch {
                completion(nil, error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.streamQnaWithAi(_:onChunk:onDone:)`. The Swift API's dual-callback
    /// shape maps directly to two separate `@escaping` closures here (plus a third for the `throws`
    /// side, since there's no single completion handler to funnel an error through once streaming
    /// has already started emitting chunks). `conversation` is `[[String: String]]` — an `NSArray`
    /// of `NSDictionary`s with `"question"`/`"answer"` string keys — rather than a dedicated mirror
    /// class, mirroring `QnaWithAiConversationTurn`'s own two-string shape with one less type to
    /// define.
    @objc public func streamQnaWithAi(
        feedbackConfigurationId: String,
        questionId: String,
        conversation: [[String: String]],
        onChunk: @escaping (String) -> Void,
        onDone: @escaping (String) -> Void,
        onError: @escaping (NSError) -> Void
    ) {
        let turns = conversation.map {
            QnaWithAiConversationTurn(question: $0["question"] ?? "", answer: $0["answer"] ?? "")
        }
        let params = QnaWithAiRequest(
            feedbackConfigurationId: feedbackConfigurationId,
            questionId: questionId,
            conversation: turns
        )
        Task {
            do {
                try await Encatch.shared.streamQnaWithAi(params, onChunk: onChunk, onDone: onDone)
            } catch {
                onError(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.uploadFile(_:)`, restricted to `UploadFileSource.bytes` — the only
    /// source `UploadFileRequest.file` supports on iOS in the first place (`.contentUri` always
    /// throws `EncatchUnsupportedOperationException` from `Core/Encatch.swift`, matching the
    /// Android-only concept it represents). `onProgress`, if provided, is invoked with 0-100
    /// percentages on an arbitrary thread, same as every other completion in this file.
    @objc public func uploadFile(
        feedbackConfigurationId: String,
        questionId: String,
        fileBytes: Data,
        fileName: String,
        mimeType: String?,
        onProgress: (@Sendable (Int) -> Void)?,
        completion: @escaping (EncatchBridgeUploadFileResponse?, NSError?) -> Void
    ) {
        Task {
            do {
                let response = try await Encatch.shared.uploadFile(UploadFileRequest(
                    feedbackConfigurationId: feedbackConfigurationId,
                    questionId: questionId,
                    file: .bytes(fileBytes, mimeType: mimeType),
                    fileName: fileName,
                    onProgress: onProgress.map { callback in { @Sendable percent in callback(percent) } }
                ))
                completion(EncatchBridgeUploadFileResponse(response), nil)
            } catch {
                completion(nil, error as NSError)
            }
        }
    }

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    /// Mirrors `Encatch.shared.clearAll()`.
    @objc public func clearAll(completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.clearAll()
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    // ============================================================================
    // Session management
    // ============================================================================

    /// Mirrors `Encatch.shared.startSession(_:)`.
    @objc public func startSession(
        _ options: EncatchBridgeStartSessionOptions?,
        completion: @escaping (NSError?) -> Void
    ) {
        Task {
            do {
                try await Encatch.shared.startSession(options?.toStartSessionOptions())
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    @objc public func pauseSession() {
        Encatch.shared.pauseSession()
    }

    @objc public func resumeSession() {
        Encatch.shared.resumeSession()
    }

    /// Mirrors `Encatch.shared.stopSession()`.
    @objc public func stopSession(completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.stopSession()
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    /// Mirrors `Encatch.shared.resetUser()`.
    @objc public func resetUser(completion: @escaping (NSError?) -> Void) {
        Task {
            do {
                try await Encatch.shared.resetUser()
                completion(nil)
            } catch {
                completion(error as NSError)
            }
        }
    }

    @objc public func setFormVisible(_ visible: Bool) {
        Encatch.shared.setFormVisible(visible)
    }

    @objc public func flushRetryQueue() {
        Encatch.shared.flushRetryQueue()
    }

    // ============================================================================
    // Events
    // ============================================================================

    /// Mirrors `Encatch.shared.on(_:)`. Registers `callback` and returns an unsubscribe closure
    /// (bridged to an Objective-C block; Kotlin/Native consumes it as a callable `() -> Unit`).
    /// There is no separate `off(callback)` entry point — see the file-level doc comment's "Design
    /// constraints" section for why (Swift closures aren't `Equatable`, and `Core/Emitter.swift`
    /// already made the same call for the pure-Swift `Encatch.on` API this wraps).
    /// Mirrors `Encatch.shared.onNetworkLog` (assignment-style, so nil clears it). Only fires
    /// when `debugMode` is enabled; the API key header arrives pre-masked to its last 5 chars.
    @objc public func setOnNetworkLog(_ callback: EncatchBridgeNetworkLogCallback?) {
        if let callback {
            Encatch.shared.onNetworkLog = { entry in callback(EncatchBridgeNetworkLogEntry(entry)) }
        } else {
            Encatch.shared.onNetworkLog = nil
        }
    }

    @discardableResult
    @objc public func onEvent(_ callback: @escaping EncatchBridgeEventCallback) -> () -> Void {
        Encatch.shared.on { eventType, payload in
            callback(eventType.wireValue, EncatchBridgeEventPayload(payload))
        }
    }

    /// Mirrors `Encatch.shared.emitEvent(_:_:)`. `eventType` must be one of `EventType.wireValue`'s
    /// wire strings (e.g. `"form:complete"`) — unrecognized values are silently ignored, matching
    /// `EventType.fromWire`'s `nil`-on-unknown behavior. `dataJSON`, if provided, must decode as a
    /// JSON object (matches `EncatchBridgeEventPayload.dataJSON`'s encoding on the way out).
    @objc public func emitEvent(_ eventType: String, formId: String?, dataJSON: String?) {
        guard let type = EventType.fromWire(eventType) else { return }
        var data: [String: JSONValue]?
        if let dataJSON, case .object(let object)? = JSONValue.parse(dataJSON) {
            data = object
        }
        Encatch.shared.emitEvent(type, EventPayload(formId: formId, timestamp: 0, data: data))
    }

    @objc public func stop() {
        Encatch.shared.stop()
    }

    // ============================================================================
    // Getters
    // ============================================================================

    @objc public var apiKey: String? { Encatch.shared.apiKey }
    @objc public var baseUrl: String { Encatch.shared.baseUrl }
    @objc public var webHost: String { Encatch.shared.webHost }
    @objc public var isFullScreen: Bool { Encatch.shared.isFullScreen }
    /// One of "light" / "dark" / "system" (`Theme.wireValue`).
    @objc public var theme: String { Encatch.shared.theme.wireValue }
    @objc public var locale: String? { Encatch.shared.locale }
    @objc public var deviceId: String? { Encatch.shared.deviceId }
    @objc public var sessionId: String? { Encatch.shared.sessionId }
    @objc public var userName: String? { Encatch.shared.userName }
    @objc public var userId: String? { Encatch.shared.userId }
    @objc public var debugMode: Bool { Encatch.shared.debugMode }

    #if canImport(UIKit)
    /// Installs the app-wide modal form host (`EncatchFormHost.install()`). `EncatchFormHost` is a
    /// caseless `enum` namespace, which Swift can't expose to Objective-C directly (only
    /// `NSObject`-rooted classes/members are `@objc`-representable) — this static method is the
    /// cinterop-visible entry point instead.
    @objc public static func installFormHost() {
        EncatchFormHost.install()
    }
    #endif
}
