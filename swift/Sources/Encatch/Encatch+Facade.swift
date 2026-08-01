import EncatchCore

/// Idiomatic Swift entry points on top of `Encatch.shared` (the Kotlin/Native-bridged singleton —
/// Kotlin/Native's Swift export already renames the generated `EncatchCoreEncatch` ObjC class to
/// plain `Encatch`, and `suspend fun`s are auto-bridged to `async throws` by Swift's completion-
/// handler importer, so most of the raw API is already directly usable as `Encatch.shared.foo(...)`.
/// This file only adds sugar where the raw bridging is awkward: `init` collides with Swift's
/// initializer syntax (renamed `doInit` on the Kotlin/Native side), and callers shouldn't have to
/// hand-roll `EncatchError` unwrapping or construct the bridged enum/sealed-class types themselves.
///
/// Other methods on `Encatch.shared` (dismissForm, submitForm, trackEvent, trackScreen,
/// identifyUser, refineText, streamQnaWithAi, ...) are already fully usable directly — this covers
/// the ones a host app most commonly touches first. Add more wrappers here as needed; the pattern
/// is the same throughout.
extension Encatch {
    /// Initializes the SDK. Sugar over the Kotlin/Native-bridged `doInit(apiKey:config:)`
    /// (renamed because `init` collides with Swift's own initializer syntax), converting the
    /// thrown `NSError` to a typed `EncatchError`.
    public func initialize(apiKey: String, config: EncatchConfig? = nil) async throws {
        do {
            try await doInit(apiKey: apiKey, config: config)
        } catch {
            throw EncatchError(error)
        }
    }

    /// Shows a form, with Swift-friendly defaults for `ShowFormOptions` (the generated
    /// initializer requires both `reset` and `context` explicitly — Kotlin default parameter
    /// values aren't exposed across the Objective-C/Swift interop boundary).
    public func showForm(formId: String, resetMode: EncatchResetMode = .always, context: [String: ContextValue] = [:]) async throws {
        do {
            let options = ShowFormOptions(reset: resetMode.kotlin, context: context)
            try await showForm(formId: formId, options: options)
        } catch {
            throw EncatchError(error)
        }
    }

    /// Sets the display theme using the real Swift `EncatchTheme` enum.
    public func setTheme(_ theme: EncatchTheme) {
        setTheme(theme: theme.kotlin)
    }

    /// Subscribes to SDK events using the real Swift `EncatchEventType` enum instead of the
    /// bridged `EventType` class hierarchy. Returns an unsubscribe closure, same as the
    /// underlying `on(callback:)`.
    @discardableResult
    public func onEvent(_ callback: @escaping (EncatchEventType, EventPayload) -> Void) -> () -> Void {
        on { type, payload in
            callback(EncatchEventType(kotlin: type), payload)
        }
    }

    /// Uploads a file using the real Swift `EncatchUploadFileSource` enum instead of the bridged
    /// `UploadFileSource` class hierarchy.
    public func uploadFile(
        feedbackConfigurationId: String,
        questionId: String,
        file: EncatchUploadFileSource,
        fileName: String,
        onProgress: ((Int32) -> Void)? = nil,
    ) async throws -> UploadFileResponse {
        do {
            let request = UploadFileRequest(
                feedbackConfigurationId: feedbackConfigurationId,
                questionId: questionId,
                file: file.kotlin,
                fileName: fileName,
                onProgress: onProgress.map { callback in { (percent: KotlinInt) in callback(Int32(percent.intValue)) } },
            )
            return try await uploadFile(params: request)
        } catch {
            throw EncatchError(error)
        }
    }
}
