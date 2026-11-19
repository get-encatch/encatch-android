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
    /// Internal (not private) so tests can use a fresh instance each; apps use `shared`.
    init() {}

    // Test seams (internal; set before `initialize`): the HTTP exchange and the storage suite.
    var transportForTesting: HttpTransport?
    var storageDefaultsForTesting: UserDefaults?
    var loggerForTesting: EncatchLogger?
    /// Ping interval, collapse window, backoff, timeout and pause lengths (tests shorten them).
    var timings = EncatchTimings()

    // Initialisation state
    private var initialized = false
    private var debugModeState = false

    // Config
    private var apiKeyState: String?
    private var apiBaseUrlState: String = DEFAULT_API_BASE_URL
    private var webHostState: String = DEFAULT_WEB_HOST
    private var isFullScreenState = false
    private var onBeforeShowForm: BeforeShowFormInterceptor?

    // Identity — read and written under [stateLock], so a request never pairs a name with another
    // user's id or signature.
    private var userNameState: String?
    private var userIdState: String?
    /// The signature of the current user's last successful secure identify, and its time.
    private var userSignature: String?
    private var userSignatureTime: String?
    /// The stored session-time signature expired (a 401): until the next successful identify,
    /// background calls are skipped and survey submits are held (RN 1.4.4 item 16).
    private var signatureExpired = false
    private var heldSubmits: [SubmitFormRequest] = []
    static let maxHeldSubmits = 10
    /// The last identify sent or in flight (dedupe); cleared when it is dropped, on reset, clearAll.
    private var lastIdentifyKey: String?
    private let stateLock = NSLock()

    private func withStateLock<T>(_ body: () -> T) -> T {
        stateLock.lock()
        defer { stateLock.unlock() }
        return body()
    }

    /// identifyUser and resetUser run one after the other, in call order (RN 1.4.4 item 13).
    private let identityOps = SerialOps()

    /// Collapse (RN 1.4.4 item 4), under [stateLock]: when each event name was last sent, and the
    /// last screen tracked. Monotonic ms.
    private var lastEventAtMs: [String: Int64] = [:]
    private var lastScreen: (name: String, atMs: Int64)?

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

    // One survey at a time (mirrors RN 1.4.4 `_scheduleAutoShow`, the Kotlin `Encatch`): the automatic
    // show waiting out its `onPageDelay`, the form whose show-form is in flight, and the open survey
    // by both its names. Read and written under [surveyLock].
    private var pendingAutoShow: Task<Void, Never>?
    /// Which scheduled show [pendingAutoShow] is: a cancelled older one must not clear a newer one's handle.
    private var pendingAutoShowToken: UUID?
    private var loadingFormId: String?
    private var openFormIds: Set<String> = []
    private let surveyLock = NSLock()

    private func withSurveyLock<T>(_ body: () -> T) -> T {
        surveyLock.lock()
        defer { surveyLock.unlock() }
        return body()
    }

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
    private var rateLimiter: RateLimiter!
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
        logger = loggerForTesting ?? DefaultEncatchLogger(debugMode: { [self] in debugModeState })

        if initialized {
            // Reconfigure rather than no-op: a host app (or this repo's tester apps switching
            // environment/API key at runtime) calling initialize() again expects the new
            // apiKey/config to actually take effect, not silently keep the first call's state.
            // Fully cancel-and-await the old ping loop and any still-draining retry-queue flush
            // before reassigning shared state below — see the comment above.
            logger.debug("SDK already initialized — reconfiguring with new apiKey/config")
            let oldSessionManager = sessionManager
            let oldRetryFlushTask = retryFlushTask
            rateLimiter?.clear()
            let teardown = Task {
                await oldSessionManager?.stopPingIntervalAndWait()
                await oldRetryFlushTask?.value
            }
            pendingReinitTask = teardown
            await teardown.value
            // the old queue's items must not go out later with the new key (nor stay held forever)
            await retryQueue?.clear()
            isSessionPaused = false
            isSessionStopped = false
            // another key or environment: no identify, expiry or collapse memory carries over
            withStateLock {
                signatureExpired = false
                heldSubmits = []
                lastIdentifyKey = nil
                lastEventAtMs = [:]
                lastScreen = nil
            }
            cancelPendingAutoShow()
            withSurveyLock {
                isFormVisible = false
                openFormIds = []
                loadingFormId = nil
            }
        }

        apiKeyState = apiKey
        apiBaseUrlState = trimTrailingSlashes(config?.apiBaseUrl ?? DEFAULT_API_BASE_URL)
        webHostState = trimTrailingSlashes(config?.webHost ?? DEFAULT_WEB_HOST)
        isFullScreenState = config?.isFullScreen ?? false
        if let theme = config?.theme { themeState = theme }
        onBeforeShowForm = config?.onBeforeShowForm

        logger.debug("Initializing SDK...")

        let storage = storageDefaultsForTesting.map { EncatchStorage(defaults: $0) } ?? EncatchStorage()
        self.storage = storage
        let rateLimiter = RateLimiter(defaultPauseMs: timings.defaultPauseMs, maxPauseMs: timings.maxPauseMs, logger: logger)
        self.rateLimiter = rateLimiter
        self.apiClient = EncatchApiClient(
            transport: transportForTesting,
            baseUrlProvider: { [self] in apiBaseUrlState },
            authStateProvider: { [self] in authState() },
            onUserPendingRetryExhausted: { [self] in
                // the server gave up on creating this user: stop pinging, back to a visitor (RN 1.4.4 item 14)
                logger.warn("User identification timed out (userPendingRetryExhausted): the user is reset")
                sessionManager?.stopPingInterval()
                try? await resetUser()
            },
            onSignatureExpired: { [self] signature, user in onSignatureExpired(signature: signature, user: user) },
            rateLimiter: rateLimiter,
            requestTimeoutMs: timings.requestTimeoutMs,
            logger: logger,
            // Full request/response payloads (including the API key) only leave the SDK when
            // the host explicitly opted into debugMode — same gate as the debug logger.
            networkLogSink: { [self] entry in
                guard debugModeState else { return }
                onNetworkLog?(entry)
            }
        )
        let retryQueue = RetryQueue(storage: storage, rateLimiter: rateLimiter, backoffBaseMs: timings.retryBackoffBaseMs, logger: logger)
        self.retryQueue = retryQueue
        // what the queue held during a pause goes out once the pause ends
        rateLimiter.onPauseEnd { bucket in
            if bucket == RetryQueue.bucket { Task { await retryQueue.flush() } }
        }
        self.sessionManager = SessionManager(
            pingIntervalMs: timings.pingIntervalMs,
            isFormVisible: { [self] in withSurveyLock { isFormVisible } },
            onPing: { [self] in await backgroundPing() }
        )
        // A stopSession() from a previous run stays in effect until startSession() or a successful
        // identify (RN 1.4.4 item 19b).
        isSessionStopped = storage.getSessionStopped()

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
            // A name stored without its id (a first identify that failed) is a visitor until the
            // next identify: the identity headers go out both or neither.
            let storedId = storage.getUserId(userName: storedName)
            let stored = storage.getUserSignature(userName: storedName)
            withStateLock {
                userNameState = storedName
                userIdState = storedId
                userSignature = stored?.signature
                userSignatureTime = stored?.time
            }
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
        // Identity changes run one after the other, so a resetUser() fired just before (and not
        // awaited) finishes first and can't undo this identify.
        await identityOps.run { await self.identifyNow(userName: userName, traits: traits, options: options) }
    }

    private func identifyNow(userName: String, traits: UserTraits?, options: IdentifyOptions?) async {
        guard initialized else { return }
        let secure = options?.secure
        // Skip a call identical to the last identify sent or still in flight (RN 1.4.4 item 3).
        let identifyKey = canonicalJSONString(.object([
            "userName": .string(userName),
            "traits": traits?.toJSONValue() ?? .null,
            "locale": options?.locale.map { .string($0) } ?? .null,
            "country": options?.country.map { .string($0) } ?? .null,
            "signature": secure.map { .string($0.signature) } ?? .null,
            "signatureTime": secure?.generatedDateTimeInUtc.map { .string($0) } ?? .null,
        ]))
        let duplicate: Bool = withStateLock {
            if lastIdentifyKey == identifyKey { return true }
            lastIdentifyKey = identifyKey
            return false
        }
        if duplicate {
            logger.debug("identifyUser \"\(userName)\" skipped: same as the last identify (duplicate)")
            return
        }

        // Switching user: take this user's stored id and signature (or none) together with the
        // name, so a request never pairs this name with another user's. With no stored id,
        // requests go as a visitor until the identify reply arrives.
        let switching = withStateLock { userNameState != userName }
        if switching {
            // a queued identify of the previous user must not go out after this one
            await retryQueue.removeQueued(label: "identifyUser")
            let storedId = storage.getUserId(userName: userName)
            let stored = storage.getUserSignature(userName: userName)
            // the previous user's held submits and signature expiry don't carry over (as on reset)
            let droppedSubmits: Int = withStateLock {
                userNameState = userName
                userIdState = storedId
                userSignature = stored?.signature
                userSignatureTime = stored?.time
                signatureExpired = false
                let dropped = heldSubmits.count
                heldSubmits = []
                return dropped
            }
            if droppedSubmits > 0 {
                logger.warn("\(droppedSubmits) held survey submit(s) dropped: another user was identified")
            }
        }
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
        // the id only when known for this name
        if let userId = withStateLock({ userNameState == userName ? userIdState : nil }) { req["userId"] = .string(userId) }
        if let secure { req["userSignature"] = .string(secure.signature) }
        if let traits { req["userAttributes"] = traits.toJSONValue() }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        let requestBody = JSONValue.object(req)
        // X-User-Name is this call's name, taken now — never the SDK's state when the queued request goes out
        let requestOptions = RequestOptions(
            identifyUserName: userName,
            signature: secure?.signature,
            signatureTime: secure?.generatedDateTimeInUtc
        )

        // While tracking is paused after a 429, hold only the latest identify: it goes out when the
        // pause ends, so a sign-in during a pause isn't lost and repeated calls don't pile up.
        if rateLimiter.isPaused(.tracking) { await retryQueue.removeQueued(label: "identifyUser") }

        await retryQueue.enqueue(label: "identifyUser", onDrop: { [weak self] in
            // dropped without succeeding: the same identify may be sent again
            guard let self else { return }
            self.withStateLock { if self.lastIdentifyKey == identifyKey { self.lastIdentifyKey = nil } }
        }) {
            let res = try await self.apiClient.post(endpoint: Endpoints.identifyUser, body: requestBody, options: requestOptions)
            await self.applyIdentifyReply(res, userName: userName, secure: secure)
        }

        Task { await self.retryQueue.flush() }
    }

    private func applyIdentifyReply(_ res: JSONValue, userName: String, secure: SecureOptions?) async {
        guard initialized else { return }
        var replyUserId: String?
        var replyFt: String?
        if case .object(let resObj) = res {
            if case .string(let value)? = resObj["userId"], !value.isEmpty { replyUserId = value }
            if case .string(let value)? = resObj["$feedbackTransactions"] { replyFt = value }
        }
        // A reply for a user who is no longer current (reset, switched or cleared since): ignored,
        // its id not stored either.
        let current: Bool = withStateLock {
            guard userNameState == userName else { return false }
            if let replyUserId { userIdState = replyUserId }
            // After a successful secure identify its signature (and time) go on every request of this
            // user; an identify without `secure` clears them.
            userSignature = secure?.signature
            userSignatureTime = secure?.signature != nil ? secure?.generatedDateTimeInUtc : nil
            return true
        }
        guard current else {
            logger.debug("identify reply for \"\(userName)\" ignored: no longer the current user")
            return
        }
        if let replyUserId { storage.setUserId(userName: userName, userId: replyUserId) }
        if let secure {
            storage.setUserSignature(userName: userName, value: .init(signature: secure.signature, time: secure.generatedDateTimeInUtc))
        } else {
            storage.clearUserSignature(userName: userName)
        }
        endSignatureExpired()
        if let replyFt {
            feedbackTransactions = replyFt
            storage.setFeedbackTransactions(identityKey: userName, value: replyFt)
        }
        let meta = res.toResponseMeta()
        sessionManager.handleResponseMeta(meta)

        let identity = withStateLock { (userNameState, userIdState) }
        EncatchInternalEmitter.shared.emit(.userIdentified(userName: identity.0, userId: identity.1))

        try? await startSession(StartSessionOptions(skipImmediatePing: true, skipImmediateTrackScreen: true))

        if let pingAgainIn = meta.pingAgainIn, pingAgainIn > 0 {
            sessionManager.scheduleNextPing(delayMs: Int64(pingAgainIn * 1000))
        }

        await handleSurveyInReply(res)
    }

    /// The identity, signature and app state the next request is built from — one snapshot.
    private func authState() -> AuthState {
        withStateLock {
            AuthState(
                apiKey: apiKeyState,
                sessionId: sessionIdState,
                userName: userNameState,
                userId: userIdState,
                userSignature: userSignature,
                userSignatureTime: userSignatureTime,
                deviceId: deviceIdState,
                appPackageName: appPackageName
            )
        }
    }

    /// The stored session-time signature expired (a 401 on a request that carried it): warn once,
    /// drop it, and wait for the next successful identify. A late 401 for an older signature (or
    /// another user) is ignored, so it can't undo a fresh secure identify.
    private func onSignatureExpired(signature: String, user: String?) {
        let (first, current): (Bool, String?) = withStateLock {
            guard userSignature == signature, userNameState == user else { return (false, nil) }
            if signatureExpired { return (false, nil) }
            signatureExpired = true
            userSignature = nil
            userSignatureTime = nil
            // the same signed identify may be sent again
            lastIdentifyKey = nil
            return (true, userNameState)
        }
        guard first else { return }
        logger.warn(
            "Secure identify signature expired (401). Call identifyUser() with a fresh signature; until then " +
                "events, screens, ping and automatic surveys are skipped and survey submits are held."
        )
        if let current { storage?.clearUserSignature(userName: current) }
    }

    var isSignatureExpired: Bool { withStateLock { signatureExpired } }

    /// A stopSession() is in effect (saved across restarts until startSession() or a successful identify).
    var sessionStopped: Bool { isSessionStopped }
    var pingActive: Bool { sessionManager?.isPingActive ?? false }
    var sessionManagerForTesting: SessionManager { sessionManager }

    /// Keeps a submit until the next successful identify (in memory, at most 10, oldest dropped).
    private func holdSubmit(_ params: SubmitFormRequest) {
        withStateLock {
            heldSubmits.append(params)
            if heldSubmits.count > Self.maxHeldSubmits { heldSubmits.removeFirst() }
        }
        logger.debug("submitForm held until the next identify (secure signature expired)")
    }

    /// A successful identify ends the expired state and sends any held submits.
    private func endSignatureExpired() {
        let held: [SubmitFormRequest] = withStateLock {
            signatureExpired = false
            let held = heldSubmits
            heldSubmits = []
            return held
        }
        for params in held { Task { try? await self.submitForm(params) } }
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
        // rate limited (429): new events are skipped until the pause ends
        if rateLimiter.isPaused(.tracking) {
            logger.debug("trackEvent \"\(eventName)\" skipped: rate limited")
            return
        }
        if isSignatureExpired {
            logger.debug("trackEvent \"\(eventName)\" skipped: secure signature expired, waiting for identify")
            return
        }
        // the same event again within the collapse window is sent once
        let window = timings.collapseWindowMs
        let collapsed: Bool = withStateLock {
            let now = monotonicNowMs()
            if let last = lastEventAtMs[eventName], now - last < window { return true }
            lastEventAtMs[eventName] = now
            if lastEventAtMs.count > 200 { lastEventAtMs = lastEventAtMs.filter { now - $0.value < window } }
            return false
        }
        if collapsed {
            logger.debug("trackEvent \"\(eventName)\" collapsed (same event within \(window) ms)")
            return
        }

        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["eventName": .string(eventName), "$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let requestBody = JSONValue.object(req)

        await retryQueue.enqueue(label: "trackEvent") {
            let res = try await self.apiClient.post(endpoint: Endpoints.trackEvent, body: requestBody)
            await self.applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)
            await self.handleSurveyInReply(res)
        }

        Task { await self.retryQueue.flush() }
    }

    /// Best-effort call for form lifecycle events; not enqueued/retried, matches `_trackFormEvent`.
    /// Public so the UI layer's WebView bridge can call it for form:started/answered/show,
    /// mirroring how the RN SDK's `useEncatchFormWebView` hook calls `Encatch._trackFormEvent`.
    public func trackFormEvent(_ eventName: String, _ feedbackConfigurationId: String?) async {
        guard initialized else { return }
        if rateLimiter.isPaused(.tracking) {
            logger.debug("survey event \(eventName) skipped: rate limited")
            return
        }
        if isSignatureExpired {
            logger.debug("survey event \(eventName) skipped: secure signature expired")
            return
        }
        // survey events are an identified user's: the server answers 403 to a visitor's (RN 1.4.4 item 8)
        guard hasIdentity else {
            logger.debug("survey event \(eventName) skipped: visitor")
            return
        }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["eventName": .string(eventName), "$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackConfigurationId { req["feedbackConfigurationId"] = .string(feedbackConfigurationId) }
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }

        do {
            let res = try await apiClient.post(endpoint: Endpoints.trackEvent, body: .object(req))
            await applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)
            await handleSurveyInReply(res)
        } catch {
            // Best-effort; matches Kotlin's runCatching { }.
        }
    }

    public func trackScreen(_ screenName: String) async throws {
        guard initialized, !isFullScreenState else { return }

        currentScreen = screenName
        // rate limited: the current screen is still remembered (it goes with the next ping)
        if rateLimiter.isPaused(.tracking) {
            logger.debug("trackScreen \"\(screenName)\" skipped: rate limited")
            return
        }
        if isSignatureExpired {
            logger.debug("trackScreen \"\(screenName)\" skipped: secure signature expired, waiting for identify")
            return
        }
        // collapsed only when the screen hasn't changed since the last one tracked (A → B → A is sent in full)
        let window = timings.collapseWindowMs
        let collapsed: Bool = withStateLock {
            let now = monotonicNowMs()
            if let last = lastScreen, last.name == screenName, now - last.atMs < window { return true }
            lastScreen = (screenName, now)
            return false
        }
        if collapsed {
            logger.debug("trackScreen \"\(screenName)\" collapsed (same screen within \(window) ms)")
            return
        }
        let deviceInfo = buildDeviceInfo(screenName: screenName)
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let requestBody = JSONValue.object(req)

        await retryQueue.enqueue(label: "trackScreen") {
            let res = try await self.apiClient.post(endpoint: Endpoints.trackScreen, body: requestBody)
            await self.applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)
            await self.handleSurveyInReply(res)
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

    /// A survey the server offers in a reply — identify, ping, track-event, track-screen and survey
    /// events alike: `nextFeedbackId`, shown after `onPageDelay` seconds; without it,
    /// `formConfigurationId` (older servers; none send it today) at once. Mirrors RN 1.4.4
    /// `_handleSurveyInReply`; before this only track-screen replies were read (AN-001).
    func handleSurveyInReply(_ res: JSONValue) async {
        guard case .object(let resObj) = res else { return }
        var next: String?
        if case .string(let value)? = resObj["nextFeedbackId"], !value.isEmpty { next = value }
        var legacy: String?
        if case .string(let value)? = resObj["formConfigurationId"], !value.isEmpty { legacy = value }
        guard let formId = next ?? legacy else { return }
        var delayMs: Int64 = 0
        if next != nil, case .number(let seconds)? = resObj["onPageDelay"] { delayMs = Int64(max(0, seconds * 1000)) }
        scheduleAutoShow(formId: formId, delayMs: delayMs)
    }

    /// One pending automatic show at a time; none while a survey is pending, loading or open.
    private func scheduleAutoShow(formId: String, delayMs: Int64) {
        if isSignatureExpired {
            logger.debug("survey \(formId) offered: skipped (secure signature expired)")
            return
        }
        let scheduled: Bool = withSurveyLock {
            if pendingAutoShow != nil || loadingFormId != nil || isFormVisible { return false }
            let token = UUID()
            pendingAutoShowToken = token
            pendingAutoShow = Task { [weak self] in
                if delayMs > 0 { try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000) }
                guard let self, !Task.isCancelled else { return }
                await self.showFormInternal(formId: formId, resetMode: .always, triggerType: .automatic, context: nil, scheduledBy: token)
            }
            return true
        }
        if !scheduled { logger.debug("survey \(formId) offered while another is pending or open: skipped (survey-open)") }
    }

    /// Cancels a survey waiting out its delay (stopSession, resetUser, clearAll, re-init).
    private func cancelPendingAutoShow() {
        withSurveyLock {
            pendingAutoShow?.cancel()
            pendingAutoShow = nil
            pendingAutoShowToken = nil
        }
    }

    private func showFormInternal(
        formId: String,
        resetMode: ResetMode,
        triggerType: TriggerType,
        context: [String: ContextValue]?,
        scheduledBy token: UUID? = nil
    ) async {
        let expired = isSignatureExpired
        let rateLimited = rateLimiter.isPaused(.showForm)
        // nil: go ahead; otherwise why the show is skipped
        let skipReason: String? = withSurveyLock {
            // the scheduled show has started: it is now "loading", no longer "pending" — if it is still the current one
            if let token {
                guard pendingAutoShowToken == token else { return "superseded by a newer automatic show" }
                pendingAutoShow = nil
                pendingAutoShowToken = nil
            }
            if rateLimited { return "rate limited" }
            if triggerType == .automatic && expired { return "secure signature expired" }
            if triggerType == .automatic && (loadingFormId != nil || isFormVisible) { return "a survey is loading or open (survey-open)" }
            if triggerType == .manual && (loadingFormId == formId || (isFormVisible && openFormIds.contains(formId))) {
                return "that survey is already loading or open"
            }
            loadingFormId = formId
            return nil
        }
        if let skipReason {
            logger.debug("show-form \(formId) skipped: \(skipReason)")
            return
        }
        await showFormRequest(formId: formId, resetMode: resetMode, triggerType: triggerType, context: context)
        withSurveyLock { if loadingFormId == formId { loadingFormId = nil } }
    }

    private func showFormRequest(
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

            // open before the UI hears of it: the host runs inside emit, and may report at once that
            // it could not show it (no window) — that report must find the survey already marked open
            withSurveyLock {
                isFormVisible = true
                openFormIds = Set([formId, feedbackConfigurationId].compactMap { $0 }.filter { !$0.isEmpty })
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

        } catch {
            // 404: "not eligible now" (the server has no "already shown") — expected, no retry
            if let apiError = error as? EncatchApiException, apiError.status == 404 {
                logger.debug("show-form \(formId): not eligible now (404)")
            } else {
                logger.warn("showForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
            }
        }
    }

    /// Both identity headers go out: a user name and the id the server gave it.
    var hasIdentity: Bool { withStateLock { userNameState != nil && userIdState != nil } }

    /// A survey the user closed before its final submit (the page's ✕, a tap outside it): the
    /// `form:dismissed` survey event (identified users) and `dismiss-form` with the form's UUID from
    /// the show-form reply — for visitors too, as the web SDK and Flutter 1.2.0 do, so a survey that
    /// stops on "Dismissed" is not offered again. The UI closes itself.
    func recordDismissal(_ formConfigurationId: String?) async {
        guard initialized else { return }
        let id = (formConfigurationId?.isEmpty == false) ? formConfigurationId : nil
        Task { await self.trackFormEvent("form:dismissed", id) }
        if let id {
            var req: [String: JSONValue] = ["formConfigurationId": .string(id), "$deviceInfo": buildDeviceInfo().toJSONValue()]
            if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
            do {
                let res = try await apiClient.post(endpoint: Endpoints.dismissForm, body: .object(req))
                await applyFeedbackTransactions(res)
                sessionManager.handleResponseMeta(res.toResponseMeta())
            } catch {
                logger.warn("dismissForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
            }
        } else {
            // dismiss-form needs the form's UUID (400 otherwise)
            logger.warn("survey closed without a form id: no dismiss-form sent")
        }
        emitEvent(.formDismissed, EventPayload(formId: id ?? "", timestamp: currentTimeMillis()))
    }

    public func dismissForm(_ formConfigurationId: String? = nil) async throws {
        guard initialized else { return }

        pendingCtaScheduler?.cancel(formId: formConfigurationId)
        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: formConfigurationId))
        withSurveyLock {
            isFormVisible = false
            openFormIds = []
        }

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
        // Secure signature expired: hold the answers until the app identifies again.
        if isSignatureExpired {
            holdSubmit(params)
            return
        }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let triggerType = params.triggerType { req["triggerType"] = .string(triggerType) }
        req["formDetails"] = params.formDetails.toJSONValue() ?? .null

        // A submit carries the user's answers, so it is never dropped for a 429: it waits for the
        // pause to end and is retried, up to 3 attempts in all. Kept in memory only.
        for attempt in 1...Self.submitMaxAttempts {
            let wait = rateLimiter.remainingMs(.submitForm)
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000) }
            do {
                let res = try await apiClient.post(endpoint: Endpoints.submitForm, body: .object(req))
                await applyFeedbackTransactions(res)
                sessionManager.handleResponseMeta(res.toResponseMeta())
                return
            } catch {
                let status = (error as? EncatchApiException)?.status
                if status == 429 && attempt < Self.submitMaxAttempts { continue }
                if status == 401, isSignatureExpired {
                    holdSubmit(params)
                    return
                }
                logger.warn("submitForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
                return
            }
        }
    }

    /// submit-form attempts in all when the API answers 429.
    static let submitMaxAttempts = 3

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
        cancelPendingAutoShow()

        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: nil))
        withSurveyLock {
            isFormVisible = false
            openFormIds = []
            loadingFormId = nil
        }

        storage?.clearAll()
        await retryQueue?.clear()
        rateLimiter?.clear()

        initialized = false
        apiKeyState = nil
        withStateLock {
            userNameState = nil
            userIdState = nil
            userSignature = nil
            userSignatureTime = nil
            signatureExpired = false
            heldSubmits = []
            lastIdentifyKey = nil
            lastEventAtMs = [:]
            lastScreen = nil
        }
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
        // a stopSession() stays in effect until startSession() or a successful identify
        guard !isSessionStopped else { return }
        sessionManager.startPingInterval()
    }

    public func stopSession() async throws {
        guard !isSessionStopped else { return }
        isSessionStopped = true
        isSessionPaused = false
        sessionManager?.stopPingInterval()
        cancelPendingAutoShow()
        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: nil))
        withSurveyLock {
            isFormVisible = false
            openFormIds = []
        }
        storage?.setSessionStopped()
    }

    public func resetUser() async throws {
        guard initialized else { throw EncatchNotInitializedException() }
        await identityOps.run { await self.resetNow() }
    }

    private func resetNow() async {
        guard initialized else { return }
        // a queued (not yet sent) identify must not bring the user back
        await retryQueue.removeQueued(label: "identifyUser")
        // Clear the in-memory identity first, so nothing is sent with a half-cleared one.
        let (previous, droppedSubmits): (String?, Int) = withStateLock {
            let previous = userNameState
            let dropped = heldSubmits.count
            userNameState = nil
            userIdState = nil
            userSignature = nil
            userSignatureTime = nil
            signatureExpired = false
            heldSubmits = []
            lastIdentifyKey = nil
            lastEventAtMs = [:]
            lastScreen = nil
            return (previous, dropped)
        }
        if droppedSubmits > 0 {
            logger.warn("\(droppedSubmits) held survey submit(s) dropped: the user was reset")
        }
        if let previous {
            storage.clearUserId(userName: previous)
            storage.clearFeedbackTransactions(identityKey: previous)
            storage.clearUserSignature(userName: previous)
        }
        storage.clearFeedbackTransactions(identityKey: "anonymous")
        storage.clearUserName()
        storage.clearSession()
        storage.clearPreferences()

        feedbackTransactions = nil
        localeState = nil
        countryState = nil
        sessionIdState = storage.getOrCreateSessionId()

        isSessionPaused = false
        isSessionStopped = false
        storage.clearSessionStopped()
        sessionManager?.stopPingInterval()
        cancelPendingAutoShow()

        EncatchInternalEmitter.shared.emit(.userIdentified(userName: nil, userId: nil))
    }

    public func setFormVisible(_ visible: Bool) {
        setFormVisible(visible, formId: nil)
    }

    /// The UI says a survey opened or closed. `formId`: the survey it is about (the id it was shown
    /// by) — a close of a survey since replaced by another is ignored, so the one on screen stays
    /// "open" for the one-at-a-time guard.
    public func setFormVisible(_ visible: Bool, formId: String?) {
        let ignored: Bool = withSurveyLock {
            if !visible, let formId, !openFormIds.isEmpty, !openFormIds.contains(formId) { return true }
            isFormVisible = visible
            if !visible { openFormIds = [] }
            return false
        }
        if ignored { logger.debug("close of \(formId ?? "") ignored: another survey is open") }
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

    /// The ping of the interval and of a server-scheduled ping: none while stopped or paused
    /// (doPing itself skips it during a 429 pause or after signature expiry).
    private func backgroundPing() async {
        if isSessionStopped || isSessionPaused {
            logger.debug("ping skipped: session \(isSessionStopped ? "stopped" : "paused")")
            return
        }
        try? await doPing()
    }

    private func doPing() async throws {
        if rateLimiter.isPaused(.tracking) {
            logger.debug("ping skipped: rate limited")
            return
        }
        if isSignatureExpired {
            logger.debug("ping skipped: secure signature expired")
            return
        }
        let deviceInfo = buildDeviceInfo(screenName: currentScreen)
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let res = try await apiClient.post(endpoint: Endpoints.ping, body: .object(req))
        await applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())
        await handleSurveyInReply(res)
    }

    private func applyFeedbackTransactions(_ res: JSONValue) async {
        guard case .object(let resObj) = res, case .string(let value)? = resObj["$feedbackTransactions"] else { return }
        feedbackTransactions = value
        storage.setFeedbackTransactions(identityKey: userName ?? "anonymous", value: value)
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
    public var userName: String? { withStateLock { userNameState } }
    public var userId: String? { withStateLock { userIdState } }
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

/// Runs async operations one after the other, in call order (identifyUser / resetUser).
final class SerialOps: @unchecked Sendable {
    private let lock = NSLock()
    private var tail: Task<Void, Never>?

    func run(_ op: @escaping @Sendable () async -> Void) async {
        await enqueue(op).value
    }

    private func enqueue(_ op: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
        lock.lock()
        defer { lock.unlock() }
        let previous = tail
        let task = Task {
            await previous?.value
            await op()
        }
        tail = task
        return task
    }
}

/// JSON with object keys sorted, so equal values give equal strings (identify dedupe key).
func canonicalJSONString(_ value: JSONValue) -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    guard let data = try? encoder.encode(value), let string = String(data: data, encoding: .utf8) else { return "" }
    return string
}
