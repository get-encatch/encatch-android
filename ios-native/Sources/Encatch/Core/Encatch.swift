import Foundation

/// Thrown by `uploadFile` for `.contentUri` sources — there's no Android-style `content://` URI
/// concept on iOS. iOS upload flows must supply raw bytes via `UploadFileSource.bytes` instead.
public struct EncatchUnsupportedOperationException: Error, Sendable, CustomStringConvertible {
    public let message: String
    public init(message: String) { self.message = message }
    public var description: String { message }
}

/// Payload for the internal event emitter backing `Encatch.on`/`emitEvent`. A plain struct rather
/// than a `(EventType, EventPayload)` tuple, since Swift generics can't be instantiated with tuple
/// type arguments.
private struct EventEmission: Sendable {
    let type: EventType
    let payload: EventPayload
}

/// The Encatch iOS SDK singleton. Mirrors `@encatch/react-native-sdk`'s exported
/// `Encatch` object 1:1 in method signatures and behavior — see `encatch.ts`.
///
/// Ported from the Kotlin `object Encatch` (a process-wide singleton) as a Swift class with a
/// single `shared` instance, since Swift has no direct `object` equivalent. Retain cycles between
/// `shared` and the closures it hands to `apiClient`/`sessionManager` are harmless — this instance
/// lives for the whole process.
public final class Encatch: @unchecked Sendable {
    public static let shared = Encatch()
    private init() {}

    // Initialisation state
    private var initialized = false
    private var debugModeState = false

    // Config
    private var apiKeyState: String?
    private var apiBaseUrlState: String = DEFAULT_API_BASE_URL
    private var webHostState: String = DEFAULT_WEB_HOST
    private var isFullScreenState = false
    private var onBeforeShowForm: BeforeShowFormInterceptor?

    // Identity
    private var userNameState: String?
    private var userIdState: String?
    private var userSignature: String?

    // Preferences
    private var localeState: String?
    private var countryState: String?
    private var themeState: Theme = .system

    // Current screen
    private var currentScreen: String?

    // Ids
    private var deviceIdState: String?
    private var sessionIdState: String?

    // Feedback transactions
    private var feedbackTransactions: String?

    // Form visibility (suppresses ping)
    private var isFormVisible = false

    // Session control
    private var isSessionPaused = false
    private var isSessionStopped = false

    // App version
    private var appVersionState: String = "1.0.0"
    private var appPackageName: String?

    // Event callbacks
    private let eventEmitter = Emitter<EventEmission>()

    // Pending pre-filled responses
    private var pendingResponses: [String: JSONValue] = [:]

    private var storage: EncatchStorage!
    private var apiClient: EncatchApiClient!
    private var retryQueue: RetryQueue!
    private var sessionManager: SessionManager!
    private var logger: EncatchLogger = DefaultEncatchLogger(debugMode: { false })

    // Tracks the retry-queue flush kicked off at the end of the most recent `initialize()`, and
    // serializes overlapping `initialize()` calls — see that method's reconfigure path.
    private var retryFlushTask: Task<Void, Never>?
    private var pendingReinitTask: Task<Void, Never>?

    /// Set by the UI layer so exit_form CTAs can open `SFSafariViewController` / the system browser.
    public var pendingCtaScheduler: PendingCompletionCtaScheduler?

    /// Debug hook: receives every completed SDK HTTP call (request + response). Only fires when
    /// `EncatchConfig.debugMode` is enabled — payloads include the API key and full bodies, so
    /// they never leave the SDK in production configurations. Survives re-`initialize()` — set
    /// it once at app startup for in-app network inspectors. May be invoked on any thread; hop
    /// to the main queue before touching UI.
    public var onNetworkLog: ((EncatchNetworkLogEntry) -> Void)?

    // ============================================================================
    // Initialisation
    // ============================================================================

    public func initialize(apiKey: String, config: EncatchConfig? = nil) async throws {
        // Wait for any prior initialize() call's reconfigure to fully finish before we touch
        // anything. Without this, two overlapping calls (or a call landing while a previous
        // reconfigure's teardown is still draining) could interleave their mutations of
        // storage/apiClient/retryQueue/sessionManager — plain vars, not actor-isolated — which
        // crashed with a pointer-authentication SIGSEGV when reproduced via the tester apps'
        // "change API key" flow.
        await pendingReinitTask?.value

        debugModeState = config?.debugMode ?? false
        logger = DefaultEncatchLogger(debugMode: { [self] in debugModeState })

        if initialized {
            // Reconfigure rather than no-op: a host app (or this repo's tester apps switching
            // environment/API key at runtime) calling initialize() again expects the new
            // apiKey/config to actually take effect, not silently keep the first call's state.
            // Fully cancel-and-await the old ping loop and any still-draining retry-queue flush
            // before reassigning shared state below — see the comment above.
            logger.debug("SDK already initialized — reconfiguring with new apiKey/config")
            let oldSessionManager = sessionManager
            let oldRetryFlushTask = retryFlushTask
            let teardown = Task {
                await oldSessionManager?.stopPingIntervalAndWait()
                await oldRetryFlushTask?.value
            }
            pendingReinitTask = teardown
            await teardown.value
            isSessionPaused = false
            isSessionStopped = false
            isFormVisible = false
        }

        apiKeyState = apiKey
        apiBaseUrlState = trimTrailingSlashes(config?.apiBaseUrl ?? DEFAULT_API_BASE_URL)
        webHostState = trimTrailingSlashes(config?.webHost ?? DEFAULT_WEB_HOST)
        isFullScreenState = config?.isFullScreen ?? false
        if let theme = config?.theme { themeState = theme }
        onBeforeShowForm = config?.onBeforeShowForm

        logger.debug("Initializing SDK...")

        let storage = EncatchStorage()
        self.storage = storage
        self.apiClient = EncatchApiClient(
            baseUrlProvider: { [self] in apiBaseUrlState },
            authStateProvider: { [self] in
                AuthState(
                    apiKey: apiKeyState,
                    sessionId: sessionIdState,
                    userName: userNameState,
                    userId: userIdState,
                    userSignature: userSignature,
                    deviceId: deviceIdState,
                    appPackageName: appPackageName
                )
            },
            onUserPendingRetryExhausted: { [self] in
                sessionManager?.stopPingInterval()
                try? await resetUser()
            },
            logger: logger,
            // Full request/response payloads (including the API key) only leave the SDK when
            // the host explicitly opted into debugMode — same gate as the debug logger.
            networkLogSink: { [self] entry in
                guard debugModeState else { return }
                onNetworkLog?(entry)
            }
        )
        self.retryQueue = RetryQueue(storage: storage)
        self.sessionManager = SessionManager(
            isFormVisible: { [self] in isFormVisible },
            onPing: { [self] in try? await doPing() }
        )

        let storedName = storage.getUserName()
        deviceIdState = storage.getOrCreateDeviceId()
        sessionIdState = storage.getOrCreateSessionId()
        let prefs = storage.getPreferences()
        let facts = collectPlatformDeviceFacts()
        appPackageName = facts.appPackageName
        appVersionState = config?.appVersion ?? facts.appVersion ?? "1.0.0"

        if let locale = prefs.locale { localeState = locale }
        if let country = prefs.country { countryState = country }

        if let storedName {
            userNameState = storedName
            userIdState = storage.getUserId(userName: storedName)
            feedbackTransactions = storage.getFeedbackTransactions(identityKey: storedName)
        } else {
            feedbackTransactions = storage.getFeedbackTransactions(identityKey: "anonymous")
        }

        initialized = true

        let flushQueue = self.retryQueue!
        retryFlushTask = Task { await flushQueue.flush() }
        pendingReinitTask = nil
        logger.debug("SDK initialized. deviceId: \(deviceIdState ?? "nil")")
    }

    // ============================================================================
    // Identity
    // ============================================================================

    public func identifyUser(userName: String, traits: UserTraits? = nil, options: IdentifyOptions? = nil) async throws {
        guard initialized else { return }

        userNameState = userName
        storage.setUserName(userName)

        if let locale = options?.locale {
            localeState = locale
            storage.setPreferences(locale: locale)
        }
        if let country = options?.country {
            countryState = country
            storage.setPreferences(country: country)
        }

        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = [
            "userName": .string(userName),
            "$deviceInfo": deviceInfo.toJSONValue(),
        ]
        if let userIdState { req["userId"] = .string(userIdState) }
        if let signature = options?.secure?.signature ?? userSignature { req["userSignature"] = .string(signature) }
        if let traits { req["userAttributes"] = traits.toJSONValue() }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        let signatureTime = options?.secure?.generatedDateTimeInUtc
        let requestBody = JSONValue.object(req)

        await retryQueue.enqueue(label: "identifyUser") {
            let res = try await self.apiClient.post(endpoint: Endpoints.identifyUser, body: requestBody, signatureTime: signatureTime)
            if case .object(let resObj) = res {
                if case .string(let userId)? = resObj["userId"] {
                    self.userIdState = userId
                    self.storage.setUserId(userName: userName, userId: userId)
                }
                if case .string(let ft)? = resObj["$feedbackTransactions"] {
                    self.feedbackTransactions = ft
                    self.storage.setFeedbackTransactions(identityKey: userName, value: ft)
                }
            }
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)

            EncatchInternalEmitter.shared.emit(.userIdentified(userName: self.userNameState, userId: self.userIdState))

            try? await self.startSession(StartSessionOptions(skipImmediatePing: true, skipImmediateTrackScreen: true))

            if let pingAgainIn = meta.pingAgainIn, pingAgainIn > 0 {
                self.sessionManager.scheduleNextPing(delayMs: Int64(pingAgainIn * 1000))
            }

            if case .object(let resObj) = res, case .string(let formConfigId)? = resObj["formConfigurationId"] {
                await self.showFormById(formConfigId, triggerType: .automatic)
            }
        }

        Task { await self.retryQueue.flush() }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    public func setLocale(_ locale: String) {
        localeState = locale
        storage.setPreferences(locale: locale)
    }

    public func setCountry(_ country: String) {
        countryState = country
        storage.setPreferences(country: country)
    }

    public func setTheme(_ theme: Theme) {
        themeState = theme
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    public func trackEvent(_ eventName: String) async throws {
        guard initialized, !isFullScreenState else { return }

        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["eventName": .string(eventName), "$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let requestBody = JSONValue.object(req)

        await retryQueue.enqueue(label: "trackEvent") {
            let res = try await self.apiClient.post(endpoint: Endpoints.trackEvent, body: requestBody)
            await self.applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)
            if case .object(let resObj) = res, case .string(let formConfigId)? = resObj["formConfigurationId"] {
                await self.showFormById(formConfigId, triggerType: .automatic)
            }
        }

        Task { await self.retryQueue.flush() }
    }

    /// Best-effort call for form lifecycle events; not enqueued/retried, matches `_trackFormEvent`.
    /// Public so the UI layer's WebView bridge can call it for form:started/answered/show,
    /// mirroring how the RN SDK's `useEncatchFormWebView` hook calls `Encatch._trackFormEvent`.
    public func trackFormEvent(_ eventName: String, _ feedbackConfigurationId: String?) async {
        guard initialized else { return }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["eventName": .string(eventName), "$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackConfigurationId { req["feedbackConfigurationId"] = .string(feedbackConfigurationId) }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        do {
            let res = try await apiClient.post(endpoint: Endpoints.trackEvent, body: .object(req))
            await applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)
            if case .object(let resObj) = res, case .string(let formConfigId)? = resObj["formConfigurationId"] {
                await showFormById(formConfigId, triggerType: .automatic)
            }
        } catch {
            // Best-effort; matches Kotlin's runCatching { }.
        }
    }

    public func trackScreen(_ screenName: String) async throws {
        guard initialized, !isFullScreenState else { return }

        currentScreen = screenName
        let deviceInfo = buildDeviceInfo(screenName: screenName)
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let requestBody = JSONValue.object(req)

        await retryQueue.enqueue(label: "trackScreen") {
            let res = try await self.apiClient.post(endpoint: Endpoints.trackScreen, body: requestBody)
            await self.applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)
            guard case .object(let resObj) = res else { return }
            if case .string(let formConfigId)? = resObj["formConfigurationId"] {
                await self.showFormById(formConfigId, triggerType: .automatic)
            }
            if case .string(let nextId)? = resObj["nextFeedbackId"] {
                var delaySeconds = 0.0
                if case .number(let d)? = resObj["onPageDelay"] { delaySeconds = d }
                Task {
                    try? await Task.sleep(nanoseconds: UInt64(max(0, delaySeconds * 1000)) * 1_000_000)
                    await self.showFormById(nextId, triggerType: .automatic, reset: .always)
                }
            }
        }

        Task { await self.retryQueue.flush() }
    }

    // ============================================================================
    // Form display
    // ============================================================================

    public func showForm(_ formId: String, options: ShowFormOptions? = nil) async throws {
        guard initialized else { return }
        await showFormInternal(formId: formId, resetMode: options?.reset ?? .always, triggerType: .manual, context: options?.context)
    }

    private func showFormById(_ formConfigurationId: String, triggerType: TriggerType, reset: ResetMode = .always) async {
        await showFormInternal(formId: formConfigurationId, resetMode: reset, triggerType: triggerType, context: nil)
    }

    private func showFormInternal(
        formId: String,
        resetMode: ResetMode,
        triggerType: TriggerType,
        context: [String: ContextValue]?
    ) async {
        pendingCtaScheduler?.cancel(formId: formId)

        let serializedContext: [String: JSONValue]? = context.map { ctx in
            ctx.reduce(into: [String: JSONValue]()) { result, entry in result[entry.key] = entry.value.toJSONValue() }
        }

        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = [
            "formSlugOrId": .string(formId),
            "triggerType": .string(triggerType.wireValue),
            "$deviceInfo": deviceInfo.toJSONValue(),
        ]
        if let localeState { req["language"] = .string(localeState) }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        do {
            let res = try await apiClient.post(endpoint: Endpoints.showForm, body: .object(req))
            await applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)

            guard case .object(let resObj) = res else { return }
            var feedbackConfigurationId: String?
            if case .string(let value)? = resObj["feedbackConfigurationId"] { feedbackConfigurationId = value }
            if let feedbackConfigurationId { pendingCtaScheduler?.cancel(formId: feedbackConfigurationId) }

            let prefill = pendingResponsesSnapshot()

            let allow = await onBeforeShowForm?(ShowFormInterceptorPayload(
                formId: formId,
                formConfig: resObj.toShowFormResponse(),
                resetMode: resetMode,
                triggerType: triggerType,
                prefillResponses: prefill,
                locale: localeState,
                theme: themeState,
                context: serializedContext
            )) ?? true

            if !allow {
                clearPendingResponses()
                return
            }

            let target = InlineSlotRegistry.shared.resolvePresentationTarget(formId: formId, feedbackConfigurationId: feedbackConfigurationId)
            let inlineSlotId: String?
            let presentation: String
            if case .inline(let slotId) = target {
                presentation = "inline"
                inlineSlotId = slotId
            } else {
                presentation = "modal"
                inlineSlotId = nil
            }

            EncatchInternalEmitter.shared.emit(.showForm(ShowFormPayload(
                formId: formId,
                formConfig: resObj.toShowFormResponse(),
                resetMode: resetMode,
                triggerType: triggerType,
                prefillResponses: prefill,
                locale: localeState,
                theme: themeState,
                context: serializedContext,
                presentation: presentation,
                inlineSlotId: inlineSlotId
            )))

            isFormVisible = true
        } catch {
            logger.warn("showForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
        }
    }

    public func dismissForm(_ formConfigurationId: String? = nil) async throws {
        guard initialized else { return }

        pendingCtaScheduler?.cancel(formId: formConfigurationId)
        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: formConfigurationId))
        isFormVisible = false

        Task { await self.trackFormEvent("form:dismissed", formConfigurationId) }

        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let formConfigurationId { req["formConfigurationId"] = .string(formConfigurationId) }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        do {
            let res = try await apiClient.post(endpoint: Endpoints.dismissForm, body: .object(req))
            await applyFeedbackTransactions(res)
            sessionManager.handleResponseMeta(res.toResponseMeta())
        } catch {
            // Best-effort; matches Kotlin's runCatching { }.
        }

        emitEvent(.formDismissed, EventPayload(formId: formConfigurationId ?? "", timestamp: currentTimeMillis()))
    }

    // ============================================================================
    // Form response helpers
    // ============================================================================

    public func addToResponse(questionId: String, value: Any?) {
        pendingResponses[questionId] = JSONValue.from(any: value)
    }

    /// Deviation from the Kotlin source: Kotlin's `getPendingResponses()` returns
    /// `Map<String, Any?>` (converted from `JsonElement` via `jsonElementToAny`). Swift returns the
    /// `JSONValue` representation directly, since that's the canonical dynamic-JSON type used
    /// throughout this port (matches what `addToResponse` stores and what `ShowFormPayload`/
    /// `FormWebViewBridge` consume) rather than round-tripping through `Any?`.
    public func getPendingResponses() -> [String: JSONValue] {
        pendingResponses
    }

    public func clearPendingResponses() {
        pendingResponses = [:]
    }

    private func pendingResponsesSnapshot() -> [String: JSONValue] {
        pendingResponses
    }

    // ============================================================================
    // Submit form
    // ============================================================================

    public func submitForm(_ params: SubmitFormRequest) async throws {
        guard initialized else { return }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let triggerType = params.triggerType { req["triggerType"] = .string(triggerType) }
        req["formDetails"] = params.formDetails.toJSONValue() ?? .null

        do {
            let res = try await apiClient.post(endpoint: Endpoints.submitForm, body: .object(req))
            await applyFeedbackTransactions(res)
            sessionManager.handleResponseMeta(res.toResponseMeta())
        } catch {
            logger.warn("submitForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
        }
    }

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

    public func refineText(_ params: RefineTextRequest) async throws -> RefineTextResponse {
        guard initialized else { throw EncatchNotInitializedException() }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = [
            "questionId": .string(params.questionId),
            "feedbackConfigurationId": .string(params.feedbackConfigurationId),
            "userText": .string(params.userText),
            "$deviceInfo": deviceInfo.toJSONValue(),
        ]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        let res = try await apiClient.post(endpoint: Endpoints.refineText, body: .object(req))
        await applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())

        guard case .object(let resObj) = res else { return RefineTextResponse() }
        var message: String?
        var refinedText: String?
        var status: Int?
        var errorValue: String?
        if case .string(let v)? = resObj["message"] { message = v }
        if case .string(let v)? = resObj["refinedText"] { refinedText = v }
        if case .number(let v)? = resObj["status"] { status = Int(v) }
        if case .string(let v)? = resObj["error"] { errorValue = v }
        return RefineTextResponse(message: message, refinedText: refinedText, status: status, error: errorValue)
    }

    public func streamQnaWithAi(
        _ params: QnaWithAiRequest,
        onChunk: @escaping (String) -> Void,
        onDone: @escaping (String) -> Void
    ) async throws {
        guard initialized else { throw EncatchNotInitializedException() }
        guard !params.feedbackConfigurationId.isEmpty, !params.questionId.isEmpty, !params.conversation.isEmpty else {
            throw EncatchApiException(
                endpoint: Endpoints.qnaWithAiStream,
                status: 0,
                responseBody: "[Encatch] feedbackConfigurationId, questionId, and conversation are required for qna-with-ai stream"
            )
        }
        try await apiClient.streamQnaWithAi(
            feedbackConfigurationId: params.feedbackConfigurationId,
            questionId: params.questionId,
            conversation: params.conversation,
            onChunk: onChunk,
            onDone: onDone
        )
    }

    public func uploadFile(_ params: UploadFileRequest) async throws -> UploadFileResponse {
        guard apiKeyState != nil else { throw EncatchNotInitializedException() }

        let bytes: Data
        let mimeType: String?
        switch params.file {
        case .bytes(let data, let mime):
            bytes = data
            mimeType = mime
        case .contentUri(let uri, let mime):
            _ = mime
            throw EncatchUnsupportedOperationException(
                message: "readContentUri is Android-only; supply UploadFileSource.bytes on iOS instead (uri=\(uri))"
            )
        }

        return try await apiClient.uploadFile(
            feedbackConfigurationId: params.feedbackConfigurationId,
            questionId: params.questionId,
            fileBytes: bytes,
            fileName: params.fileName,
            mimeType: mimeType,
            onProgress: params.onProgress.map { callback in { percent in callback(percent) } }
        )
    }

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    public func clearAll() async throws {
        sessionManager?.stopPingInterval()

        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: nil))
        isFormVisible = false

        storage?.clearAll()

        initialized = false
        apiKeyState = nil
        userNameState = nil
        userIdState = nil
        userSignature = nil
        feedbackTransactions = nil
        deviceIdState = nil
        sessionIdState = nil
        localeState = nil
        countryState = nil
        themeState = .system
        currentScreen = nil
        pendingResponses = [:]
        isSessionPaused = false
        isSessionStopped = false

        EncatchInternalEmitter.shared.emit(.userIdentified(userName: nil, userId: nil))
    }

    // ============================================================================
    // Session management
    // ============================================================================

    public func startSession(_ options: StartSessionOptions? = nil) async throws {
        guard initialized else { return }

        isSessionStopped = false
        isSessionPaused = false
        storage.clearSessionStopped()

        if isFullScreenState { return }

        sessionIdState = storage.getOrCreateSessionId()
        sessionManager.startPingInterval()

        if options?.skipImmediatePing != true {
            Task { try? await self.doPing() }
        }
        if options?.skipImmediateTrackScreen != true {
            if let screen = currentScreen {
                Task { try? await self.trackScreen(screen) }
            }
        }
    }

    public func pauseSession() {
        guard !isSessionPaused else { return }
        isSessionPaused = true
        sessionManager.stopPingInterval()
    }

    public func resumeSession() {
        guard isSessionPaused else { return }
        isSessionPaused = false
        sessionManager.startPingInterval()
    }

    public func stopSession() async throws {
        guard !isSessionStopped else { return }
        isSessionStopped = true
        isSessionPaused = false
        sessionManager?.stopPingInterval()
        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: nil))
        isFormVisible = false
        storage?.setSessionStopped()
    }

    public func resetUser() async throws {
        guard initialized else { throw EncatchNotInitializedException() }
        if let name = userNameState {
            storage.clearUserId(userName: name)
            storage.clearFeedbackTransactions(identityKey: name)
        }
        storage.clearFeedbackTransactions(identityKey: "anonymous")
        storage.clearUserName()
        storage.clearSession()
        storage.clearPreferences()

        userNameState = nil
        userIdState = nil
        userSignature = nil
        feedbackTransactions = nil
        localeState = nil
        countryState = nil
        sessionIdState = storage.getOrCreateSessionId()

        isSessionPaused = false
        isSessionStopped = false
        sessionManager?.stopPingInterval()

        EncatchInternalEmitter.shared.emit(.userIdentified(userName: nil, userId: nil))
    }

    public func setFormVisible(_ visible: Bool) {
        isFormVisible = visible
    }

    /// Flushes the offline retry queue. The RN SDK does this automatically on `AppState` foreground;
    /// on iOS the UI layer calls this from `UIApplication.willEnterForegroundNotification`.
    public func flushRetryQueue() {
        guard let retryQueue else { return }
        Task { await retryQueue.flush() }
    }

    // ============================================================================
    // Events
    // ============================================================================

    /// Registers a listener; returns a closure that removes it. Deviation from the Kotlin source:
    /// Swift closures aren't `Equatable`, so a standalone `off(callback)` overload (which in Kotlin
    /// relies on reference-equality of the function value) isn't portable — use the returned
    /// unsubscribe closure instead (same shape `Emitter<T>` uses).
    @discardableResult
    public func on(_ callback: @escaping EventCallback) -> () -> Void {
        eventEmitter.on { emission in callback(emission.type, emission.payload) }
    }

    public func emitEvent(_ eventType: EventType, _ payload: EventPayload) {
        var full = payload
        full.timestamp = currentTimeMillis()
        eventEmitter.emit(EventEmission(type: eventType, payload: full))
    }

    public func stop() {
        sessionManager?.stopPingInterval()
    }

    // ============================================================================
    // Ping
    // ============================================================================

    private func doPing() async throws {
        let deviceInfo = buildDeviceInfo(screenName: currentScreen)
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let res = try await apiClient.post(endpoint: Endpoints.ping, body: .object(req))
        await applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())
        if case .object(let resObj) = res, case .string(let formConfigId)? = resObj["formConfigurationId"] {
            await showFormById(formConfigId, triggerType: .automatic)
        }
    }

    private func applyFeedbackTransactions(_ res: JSONValue) async {
        guard case .object(let resObj) = res, case .string(let value)? = resObj["$feedbackTransactions"] else { return }
        feedbackTransactions = value
        storage.setFeedbackTransactions(identityKey: userNameState ?? "anonymous", value: value)
    }

    // ============================================================================
    // Device info builder
    // ============================================================================

    private func buildDeviceInfo(screenName: String? = nil) -> ApiDeviceInfo {
        let facts = collectPlatformDeviceFacts()
        return ApiDeviceInfo(
            deviceOs: SDK_PLATFORM,
            deviceVersion: facts.osVersion,
            deviceOsVersion: facts.osVersion,
            deviceType: "native",
            deviceSize: nil,
            sdkVersion: SDK_VERSION,
            appVersion: appVersionState,
            app: appPackageName,
            deviceLanguage: facts.deviceLocale,
            userLanguage: localeState ?? facts.deviceLocale,
            countryCode: countryState,
            preferredTheme: themeState.wireValue,
            timezone: facts.timezone,
            urlOrScreenName: screenName ?? currentScreen
        )
    }

    // ============================================================================
    // Getters
    // ============================================================================

    public var isInitialized: Bool { initialized }
    public var apiKey: String? { apiKeyState }
    public var baseUrl: String { apiBaseUrlState }
    public var webHost: String { webHostState }
    public var isFullScreen: Bool { isFullScreenState }
    public var theme: Theme { themeState }
    public var locale: String? { localeState }
    public var deviceId: String? { deviceIdState }
    public var sessionId: String? { sessionIdState }
    public var userName: String? { userNameState }
    public var userId: String? { userIdState }
    public var debugMode: Bool { debugModeState }
}

private func trimTrailingSlashes(_ value: String) -> String {
    var result = value
    while result.hasSuffix("/") {
        result.removeLast()
    }
    return result
}

private extension ApiDeviceInfo {
    func toJSONValue() -> JSONValue {
        var result: [String: JSONValue] = [:]
        if let deviceOs { result["$deviceOs"] = .string(deviceOs) }
        if let deviceVersion { result["$deviceVersion"] = .string(deviceVersion) }
        if let deviceOsVersion { result["$deviceOsVersion"] = .string(deviceOsVersion) }
        if let deviceType { result["$deviceType"] = .string(deviceType) }
        if let deviceSize { result["$deviceSize"] = .string(deviceSize) }
        if let sdkVersion { result["$sdkVersion"] = .string(sdkVersion) }
        if let appVersion { result["$appVersion"] = .string(appVersion) }
        if let app { result["$app"] = .string(app) }
        if let deviceLanguage { result["$deviceLanguage"] = .string(deviceLanguage) }
        if let userLanguage { result["$userLanguage"] = .string(userLanguage) }
        if let countryCode { result["$countryCode"] = .string(countryCode) }
        if let preferredTheme { result["$preferredTheme"] = .string(preferredTheme) }
        if let timezone { result["$timezone"] = .string(timezone) }
        if let urlOrScreenName { result["$urlOrScreenName"] = .string(urlOrScreenName) }
        return .object(result)
    }
}

private extension UserTraits {
    func toJSONValue() -> JSONValue {
        var result: [String: JSONValue] = [:]
        if let set { result["$set"] = .object(set) }
        if let setOnce { result["$setOnce"] = .object(setOnce) }
        if let increment { result["$increment"] = .object(increment.mapValues { .number($0) }) }
        if let decrement { result["$decrement"] = .object(decrement.mapValues { .number($0) }) }
        if let unset { result["$unset"] = .array(unset.map { .string($0) }) }
        return .object(result)
    }
}

private let isoDateFormatter = ISO8601DateFormatter()

private extension ContextValue {
    func toJSONValue() -> JSONValue {
        switch self {
        case .string(let value): return .string(value)
        case .number(let value): return .number(value)
        case .boolean(let value): return .bool(value)
        case .date(let epochMillis):
            let date = Date(timeIntervalSince1970: Double(epochMillis) / 1000.0)
            return .string(isoDateFormatter.string(from: date))
        }
    }
}

// internal (not private) so EncatchTests can exercise the top-level parse contract directly —
// notably that projectI18nFileUrl is read from the response root, never from nested config.
extension Dictionary where Key == String, Value == JSONValue {
    func toShowFormResponse() -> ShowFormResponse {
        var feedbackConfigurationId = ""
        if case .string(let v)? = self["feedbackConfigurationId"] { feedbackConfigurationId = v }
        var feedbackIdentifier: String?
        if case .string(let v)? = self["feedbackIdentifier"] { feedbackIdentifier = v }
        var triggerType: TriggerType?
        if case .string(let v)? = self["triggerType"] { triggerType = v == "automatic" ? .automatic : .manual }
        var formConfiguration: [String: JSONValue]?
        if case .object(let v)? = self["formConfiguration"] { formConfiguration = v }
        var contact: [String: JSONValue]?
        if case .object(let v)? = self["contact"] { contact = v }
        var partialResponseEnabled: Bool?
        if case .bool(let v)? = self["partialResponseEnabled"] { partialResponseEnabled = v }
        var projectI18nFileUrl: String?
        if case .string(let v)? = self["projectI18nFileUrl"] { projectI18nFileUrl = v }
        var pingAgainIn: Double?
        if case .number(let v)? = self["pingAgainIn"] { pingAgainIn = v }
        var pingOnNextPageVisit: Bool?
        if case .bool(let v)? = self["pingOnNextPageVisit"] { pingOnNextPageVisit = v }
        var feedbackTransactions: String?
        if case .string(let v)? = self["$feedbackTransactions"] { feedbackTransactions = v }

        return ShowFormResponse(
            feedbackConfigurationId: feedbackConfigurationId,
            feedbackIdentifier: feedbackIdentifier,
            triggerType: triggerType,
            formConfiguration: formConfiguration,
            questionnaireFields: self["questionnaireFields"],
            otherConfigurationProperties: self["otherConfigurationProperties"],
            appearanceProperties: self["appearanceProperties"],
            partialResponseEnabled: partialResponseEnabled,
            contact: contact,
            projectI18nFileUrl: projectI18nFileUrl,
            pingAgainIn: pingAgainIn,
            pingOnNextPageVisit: pingOnNextPageVisit,
            feedbackTransactions: feedbackTransactions
        )
    }
}
