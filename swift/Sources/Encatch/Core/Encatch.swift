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
    /// A confirmed identity (RN 1.5.0 item 2): the user was restored at init with their id, or their
    /// identify succeeded. Kept through a user switch until the new user's identify succeeds;
    /// cleared by resetUser() (also the server's "retries exhausted" reset) and clearAll().
    private var confirmedIdentity = false
    /// The user's data for this app run when `persistUserAcrossRestarts` is off; kept across a re-init.
    private let memoryUserStore = MemoryUserStore()
    /// Who each survey was shown to (handover 6b): the user current right before its show-form
    /// request (nil: a visitor), by the form's ids (the id asked for and the reply's
    /// feedbackConfigurationId), under [stateLock]. A submit's answers belong to that user; kept until
    /// the survey closes or is dismissed, so a repeated final submit resolves the same way. A new show
    /// of the same survey overwrites it.
    private struct Owner {
        let userName: String?
    }
    private var shownTo: [String: Owner] = [:]
    /// Bumped by clearAll(), under [stateLock]: a submit waiting out a rate-limit pause since then is dropped.
    private var clearGeneration = 0

    /// The survey with these ids closed or was dismissed: its answers no longer need an owner.
    private func forgetShown(_ ids: [String?]) {
        let keys = ids.compactMap { $0 }.filter { !$0.isEmpty }
        if keys.isEmpty { return }
        withStateLock { for key in keys { shownTo[key] = nil } }
    }

    /// Survey submits held while the signature is expired, each with the user who made it.
    private struct HeldSubmit {
        let userName: String?
        let params: SubmitFormRequest
    }
    private var heldSubmits: [HeldSubmit] = []
    /// `EncatchConfig.onSignatureExpired`, under [stateLock].
    private var onSignatureExpired: SignatureExpiredListener?
    /// Users already told through [onSignatureExpired], under [stateLock]. A rejected new signature
    /// isn't told again for the same user until an identify for them succeeds, else a signature the
    /// app's server keeps getting wrong would loop (callback, identify, 401, callback); entering the
    /// expired state always tells, and adds the user. A successful identify removes its user;
    /// resetUser(), clearAll() and a re-init clear it.
    private var expiryToldUsers: Set<String> = []
    static let maxHeldSubmits = 10
    /// identifyUser() dedupe, for this app run only (RN SDK 1.5.0, PostHog-style): what the last
    /// identify sent (or still in flight) carried. Under [stateLock]. Cleared when that identify fails
    /// or is dropped, by a different user's identify, resetUser, clearAll, stopSession and a signature
    /// expiry (the next identify must go out; repeats still collapse).
    private var identifyMemory: IdentifyMemory?

    private struct IdentifyMemory {
        let name: String
        /// The signature and its time, as sent.
        let optionsKey: String
        /// The fingerprint of the last traits sent for this user, and the identify that sent them.
        var traitsKey: String?
        var traitsToken: UUID?
        /// The locale / country the last identify for this user carried.
        let locale: String?
        let country: String?
        /// The identify this memory belongs to.
        let token: UUID
    }

    // setUserProperties (RN SDK 1.5.0) ------------------------------------------------------------
    /// The user it may update, under [stateLock]: their identify succeeded, or they were restored with
    /// their id at launch. Cleared on a user switch (until the new user's identify succeeds),
    /// resetUser and clearAll.
    private var confirmedName: String?
    /// Identifies queued or retrying, by user name, under [stateLock]: setUserProperties waits for them.
    private var pendingIdentifies: [String: [UUID: AsyncSignal]] = [:]
    /// Fired (then replaced) by stop(), clearAll() and a re-init, under [stateLock]: waits started
    /// before then give up.
    private var lifecycleRelease = AsyncSignal()
    /// The shared "no survey open" signal, under [surveyLock]; one watcher per signal.
    private var surveyIdle: AsyncSignal?
    /// setUserProperties updates go out one after the other, in call order (under [stateLock]): the
    /// next is queued only when the previous one is sent or dropped.
    private var propsWaiting: [(token: UUID, release: AsyncSignal, start: @Sendable () -> Void)] = []
    /// The update being sent: claimed when it is taken off [propsWaiting], in the same lock, so two
    /// can never start at once.
    private var propsBusy: UUID?
    /// setUserProperties calls wait their turn here, in call order, from the moment they're made: two
    /// waiting at once (for a survey, or for one identify) must not go out reversed. Not [identityOps],
    /// so an identify or reset is never held up by a waiting update.
    private let propsOrder = SerialOps()

    /// Registers an identify of [userName] as pending; the returned function ends it (sent or dropped).
    private func addPendingIdentify(_ userName: String) -> Once {
        let key = UUID()
        let signal = AsyncSignal()
        withStateLock { pendingIdentifies[userName, default: [:]][key] = signal }
        return Once { [weak self] in
            signal.fire()
            self?.withStateLock {
                self?.pendingIdentifies[userName]?[key] = nil
                if self?.pendingIdentifies[userName]?.isEmpty == true { self?.pendingIdentifies[userName] = nil }
            }
        }
    }

    /// Ends every pending identify: the queue was emptied without reporting them (re-init, clearAll).
    private func releasePendingIdentifies() {
        let all: [AsyncSignal] = withStateLock {
            let all = pendingIdentifies.values.flatMap(\.values)
            pendingIdentifies = [:]
            return all
        }
        for signal in all { signal.fire() }
    }

    private func releaseLifecycleWaits() {
        let old: AsyncSignal = withStateLock {
            let old = lifecycleRelease
            lifecycleRelease = AsyncSignal()
            return old
        }
        old.fire()
        checkSurveyIdle(release: true)
    }

    /// A survey is loading, on screen, or about to be shown (an automatic show waiting out its delay).
    private func isSurveyOpen() -> Bool {
        withSurveyLock { loadingFormId != nil || isFormVisible || pendingAutoShow != nil }
    }

    private func awaitSurveyIdle(_ release: AsyncSignal) async {
        while initialized, isSurveyOpen(), !release.isFired {
            await AsyncSignal.either(surveyIdleSignal(), release)
        }
    }

    /// The shared signal, and its watcher when it is new (a new one only after the last fired).
    private func surveyIdleSignal() -> AsyncSignal {
        let (signal, fresh): (AsyncSignal, Bool) = withSurveyLock {
            if let surveyIdle { return (surveyIdle, false) }
            let signal = AsyncSignal()
            surveyIdle = signal
            return (signal, true)
        }
        if fresh {
            let pollMs = timings.surveyIdlePollMs
            Task { [weak self] in
                while !signal.isFired {
                    try? await Task.sleep(nanoseconds: UInt64(max(1, pollMs)) * 1_000_000)
                    guard let self else { return }
                    self.checkSurveyIdle()
                }
            }
        }
        return signal
    }

    /// Wakes the survey waiters once no survey is open ([release]: wake them regardless).
    private func checkSurveyIdle(release: Bool = false) {
        if !release, initialized, isSurveyOpen() { return }
        let signal: AsyncSignal? = withSurveyLock {
            let signal = surveyIdle
            surveyIdle = nil
            return signal
        }
        signal?.fire()
    }

    private func pumpUserProps() {
        let next: (@Sendable () -> Void)? = withStateLock {
            guard propsBusy == nil else { return nil }
            // stop() / clearAll() since they were queued: dropped here, in a loop (starting each
            // released one to have it finish would nest one call in another, deep enough to
            // overflow the stack with many waiting)
            while let first = propsWaiting.first, first.release.isFired { propsWaiting.removeFirst() }
            guard !propsWaiting.isEmpty else { return nil }
            let next = propsWaiting.removeFirst()
            propsBusy = next.token
            return next.start
        }
        next?()
    }

    /// A later identifyUser() with traits is sent again: setUserProperties may have changed them.
    private func forgetIdentifyTraits() {
        withStateLock {
            identifyMemory?.traitsKey = nil
            identifyMemory?.traitsToken = nil
        }
    }

    /// A dropped identify forgets what it set: all of it while it owns the memory, else its traits.
    private func forgetDroppedIdentify(_ token: UUID) {
        withStateLock {
            guard let memory = identifyMemory else { return }
            if memory.token == token {
                identifyMemory = nil
            } else if memory.traitsToken == token {
                identifyMemory?.traitsKey = nil
                identifyMemory?.traitsToken = nil
            }
        }
    }
    private let stateLock = NSLock()
    /// Serialises the signature in memory with its storage write (a 401 dropping it, an identify
    /// setting it), so a late drop can't wipe a fresh identify's stored signature. Never taken by a
    /// public synchronous API, and the storage write happens outside [stateLock]: UserDefaults
    /// notifies observers synchronously, and one may call back into the SDK.
    private let signatureWriteLock = NSLock()

    private func withSignatureWrite<T>(_ body: () -> T) -> T {
        signatureWriteLock.lock()
        defer { signatureWriteLock.unlock() }
        return body()
    }

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
            // the cleared queue reports nothing: end what waits on it
            releasePendingIdentifies()
            releaseLifecycleWaits()
            let droppedUpdates: Int = withStateLock {
                let dropped = propsWaiting.count
                propsWaiting = []
                propsBusy = nil
                return dropped
            }
            if droppedUpdates > 0 {
                logger.debug("\(droppedUpdates) setUserProperties update(s) not sent: the SDK was re-initialized")
            }
            isSessionPaused = false
            isSessionStopped = false
            // another key or environment: no identify, expiry or collapse memory carries over
            withStateLock {
                signatureExpired = false
                heldSubmits = []
                expiryToldUsers = []
                // nor who its surveys were shown to; a submit waiting out a pause is dropped
                shownTo = [:]
                clearGeneration += 1
                identifyMemory = nil
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
        withStateLock { onSignatureExpired = config?.onSignatureExpired }

        logger.debug("Initializing SDK...")

        let defaults = storageDefaultsForTesting ?? .standard
        let storage: EncatchStorage
        if config?.persistUserAcrossRestarts ?? true {
            memoryUserStore.clear()
            storage = EncatchStorage(defaults: defaults)
        } else {
            // nothing about the user is kept on the device, including what an earlier run saved
            storage = EncatchStorage(defaults: defaults, userMemory: memoryUserStore)
            storage.deleteSavedUserData()
        }
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
            onUnauthorized: { [self] reply in onUnauthorized(reply) },
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
                confirmedIdentity = storedId != nil
                confirmedName = storedId != nil ? storedName : nil
            }
            feedbackTransactions = storage.getFeedbackTransactions(identityKey: storedName)
        } else {
            withStateLock {
                userNameState = nil
                userIdState = nil
                userSignature = nil
                userSignatureTime = nil
                confirmedIdentity = false
                confirmedName = nil
            }
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
        // Traits a request body can't carry would fail inside the queue: refuse now.
        if hasUnsendableValue(traits) {
            logger.warn("identifyUser \"\(userName)\" ignored: traits contain a value that can't be sent (a number that isn't finite).")
            return
        }

        // Dedupe (RN SDK 1.5.0). Sent: the first identify of this app run or a different user, a
        // changed signature, a locale / country that differs from what the last identify carried,
        // new traits. Not sent: the same user again with no traits, or with the same traits, as the
        // last identify sent or still in flight. A skipped call changes no memory.
        let traitsKey = traitsForDedupe(traits).map(canonicalJSONString)
        let optionsKey = canonicalJSONString(.object([
            "signature": secure.map { .string($0.signature) } ?? .null,
            "signatureTime": secure?.generatedDateTimeInUtc.map { .string($0) } ?? .null,
        ]))
        let memoryToken = UUID()
        let duplicate: Bool = withStateLock {
            let memory = identifyMemory
            let sameUser = memory?.name == userName
            if let memory, sameUser, optionsKey == memory.optionsKey,
               options?.locale == nil || options?.locale == memory.locale,
               options?.country == nil || options?.country == memory.country,
               traitsKey == nil || traitsKey == memory.traitsKey {
                return true
            }
            // another user starts with no traits, locale or country remembered
            let kept = sameUser ? memory : nil
            identifyMemory = IdentifyMemory(
                name: userName,
                optionsKey: optionsKey,
                traitsKey: traitsKey ?? kept?.traitsKey,
                traitsToken: traitsKey != nil ? memoryToken : kept?.traitsToken,
                locale: options?.locale ?? kept?.locale,
                country: options?.country ?? kept?.country,
                token: memoryToken
            )
            return false
        }
        if duplicate {
            logger.debug(
                "identifyUser \"\(userName)\" skipped: same user and traits as the last identify in this app run. " +
                    "To update properties again on purpose (for example a repeated $increment), use setUserProperties(\"\(userName)\", traits)."
            )
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
                // not confirmed until this user's identify succeeds (setUserProperties waits for it)
                confirmedName = nil
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
                logger.warn("\(droppedSubmits) held survey submit(s) dropped: identifyUser() switched to another user")
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

        // setUserProperties for this user waits until this identify is sent or dropped
        let finishPending = addPendingIdentify(userName)
        await retryQueue.enqueue(label: "identifyUser", onDrop: { [weak self] in
            finishPending()
            // dropped without succeeding: the same identify may be sent again (its traits too, even
            // if a later identify without traits took over the rest of the memory)
            self?.forgetDroppedIdentify(memoryToken)
        }) {
            let res = try await self.apiClient.post(endpoint: Endpoints.identifyUser, body: requestBody, options: requestOptions)
            await self.applyIdentifyReply(res, userName: userName, secure: secure)
            finishPending()
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
        let current: Bool = withSignatureWrite {
        let current: Bool = withStateLock {
            guard userNameState == userName else { return false }
            if let replyUserId { userIdState = replyUserId }
            // the identify succeeded: this user is now the confirmed identity
            if userIdState != nil {
                confirmedIdentity = true
                confirmedName = userName
            }
            // After a successful secure identify its signature (and time) go on every request of this
            // user; an identify without `secure` clears them.
            userSignature = secure?.signature
            userSignatureTime = secure?.signature != nil ? secure?.generatedDateTimeInUtc : nil
            // told again about this user's next expiry
            expiryToldUsers.remove(userName)
            return true
        }
        guard current else { return false }
        if let replyUserId { storage.setUserId(userName: userName, userId: replyUserId) }
        if let secure {
            storage.setUserSignature(userName: userName, value: .init(signature: secure.signature, time: secure.generatedDateTimeInUtc))
        } else {
            storage.clearUserSignature(userName: userName)
        }
        return true
        }
        guard current else {
            logger.debug("identify reply for \"\(userName)\" ignored: no longer the current user")
            return
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

    // ============================================================================
    // setUserProperties
    // ============================================================================

    /// Updates the identified user's properties, with the same operations as identifyUser's traits
    /// (`$set`, `$setOnce`, `$increment`, `$decrement`, `$unset`), through identify-user. Never
    /// deduped: every call is sent.
    ///
    /// `userName` must be the current user, and their identify must have succeeded (or they were
    /// restored with their id at launch); otherwise the call is ignored, so a pending or failed switch
    /// can't update the wrong person. Waits for an identify of that user still in flight, and for an
    /// open survey to close. Returns once the update is queued or ignored.
    ///
    /// It counts against the identify-user rate limit (about 5 per minute per user; a 429 pauses all
    /// tracking). An update with `$increment` or `$decrement` is sent once, never retried, so a
    /// timeout can't apply it twice.
    public func setUserProperties(userName: String, traits: UserTraits) async throws {
        await startSetUserProperties(userName: userName, traits: traits)?.value
    }

    /// Checks the traits and takes the call's place among the identity calls now (so it sees every
    /// identify and reset made before it); the returned task does the waiting and queues the update.
    /// The SwiftUI context starts it without waiting for it.
    @discardableResult
    func startSetUserProperties(userName: String, traits: UserTraits) -> Task<Void, Never>? {
        guard initialized else { return nil }
        if hasUnsendableValue(traits) {
            logger.warn("setUserProperties(\"\(userName)\") ignored: traits contain a value that can't be sent (a number that isn't finite).")
            return nil
        }
        guard traitsForDedupe(traits) != nil else {
            logger.debug("setUserProperties ignored: no traits to set.")
            return nil
        }
        // what will be sent, taken now (UserTraits is a value type: the caller's later changes don't reach it)
        let wire = traits.toJSONValue()
        let counts = !(traits.increment?.isEmpty ?? true) || !(traits.decrement?.isEmpty ?? true)

        // Identity calls made before this one run first; then wait, outside the identity chain (so a
        // resetUser() isn't held up), for an identify of this user still in flight and for an open
        // survey to close.
        let release = withStateLock { lifecycleRelease }
        let lookUpPending: @Sendable (PendingBox) -> @Sendable () async -> Void = { box in
            { box.signals = self.withStateLock { Array(self.pendingIdentifies[userName]?.values ?? [:].values) } }
        }
        let first = PendingBox()
        let firstLookup = identityOps.start(lookUpPending(first))
        return propsOrder.start {
            await firstLookup.value
            await self.waitThenSetUserProperties(userName: userName, wire: wire, counts: counts, release: release, first: first, lookUpPending: lookUpPending)
        }
    }

    private func waitThenSetUserProperties(
        userName: String,
        wire: JSONValue,
        counts: Bool,
        release: AsyncSignal,
        first: PendingBox,
        lookUpPending: @escaping @Sendable (PendingBox) -> @Sendable () async -> Void
    ) async {
        // [first] keeps the identifies made before this call even if they finished since; each turn of
        // the loop also looks again (identify calls made while this one waited its turn count too)
        var earlier: [AsyncSignal] = first.signals
        while true {
            let box = PendingBox()
            await identityOps.run(lookUpPending(box))
            let signals = earlier + box.signals
            earlier = []
            if !signals.isEmpty {
                for signal in signals {
                    await AsyncSignal.either(signal, release)
                    if release.isFired { break }
                }
            } else if isSurveyOpen() {
                await awaitSurveyIdle(release)
            } else {
                break
            }
            if release.isFired || !initialized { return }
        }
        await identityOps.run { await self.setUserPropertiesNow(userName: userName, wire: wire, counts: counts, release: release) }
    }

    final class PendingBox: @unchecked Sendable {
        var signals: [AsyncSignal] = []
    }

    private func setUserPropertiesNow(userName: String, wire: JSONValue, counts: Bool, release: AsyncSignal) async {
        guard initialized, !release.isFired else { return }
        let (current, userId, confirmed, expired) = withStateLock { (userNameState, userIdState, confirmedName, signatureExpired) }
        guard let current else {
            logger.debug("setUserProperties ignored: no user is identified. Call identifyUser() first.")
            return
        }
        guard current == userName else {
            logger.warn("setUserProperties(\"\(userName)\") ignored: the current user is \"\(current)\".")
            return
        }
        guard let userId, confirmed == userName else {
            logger.warn("setUserProperties(\"\(userName)\") ignored: \"\(userName)\" isn't identified (identifyUser failed or was never called).")
            return
        }
        if expired {
            logger.debug("setUserProperties(\"\(userName)\") ignored: secure signature expired, waiting for identify")
            return
        }
        if rateLimiter.isPaused(.tracking) {
            logger.warn("setUserProperties ignored: rate limited, try again later.")
            return
        }

        // a later identifyUser() with traits is sent again (the properties may have changed since)
        forgetIdentifyTraits()

        var req: [String: JSONValue] = [
            "userName": .string(userName),
            "userId": .string(userId),
            "$deviceInfo": buildDeviceInfo().toJSONValue(),
            "userAttributes": wire,
        ]
        if let feedbackTransactions { req["$feedbackTransactions"] = .string(feedbackTransactions) }
        let body = JSONValue.object(req)

        let token = UUID()
        let finish = Once { [weak self] in
            guard let self else { return }
            let mine: Bool = self.withStateLock {
                guard self.propsBusy == token else { return false }
                self.propsBusy = nil
                return true
            }
            if mine { self.pumpUserProps() }
        }
        // runs with the pump already claimed for this update ([propsBusy] == token)
        let start: @Sendable () -> Void = { [weak self] in
            guard let self else { return }
            // stop() / clearAll() since it was queued: it was for the user being cleared
            if release.isFired {
                finish()
                return
            }
            Task { await self.queueUserProperties(userName: userName, userId: userId, body: body, counts: counts, release: release, finish: finish) }
        }
        withStateLock { propsWaiting.append((token: token, release: release, start: start)) }
        pumpUserProps()
    }

    private func queueUserProperties(userName: String, userId: String, body: JSONValue, counts: Bool, release: AsyncSignal, finish: Once) async {
        guard let retryQueue else {
            finish()
            return
        }
        await retryQueue.enqueue(
            label: "setUserProperties",
            maxRetries: counts ? 1 : RetryQueue.maxRetries,
            onDrop: { finish() }
        ) { [weak self] in
            guard let self else {
                finish()
                return
            }
            // checked again just before sending: a reset or switch may have happened
            let (current, currentId, confirmed, expired, signature, signatureTime) = self.withStateLock {
                (self.userNameState, self.userIdState, self.confirmedName, self.signatureExpired, self.userSignature, self.userSignatureTime)
            }
            if release.isFired || current != userName || confirmed != userName || currentId != userId {
                self.logger.debug("setUserProperties(\"\(userName)\") not sent: the user changed")
                finish()
                return
            }
            if expired {
                self.logger.debug("setUserProperties(\"\(userName)\") ignored: secure signature expired, waiting for identify")
                finish()
                return
            }
            // a survey opened meanwhile: send after it closes (an identify-user while a survey is
            // open can drop it from the person's candidates on the server, so its submit fails)
            if self.isSurveyOpen() {
                Task {
                    await self.awaitSurveyIdle(release)
                    if release.isFired {
                        finish()
                    } else {
                        await self.queueUserProperties(userName: userName, userId: userId, body: body, counts: counts, release: release, finish: finish)
                    }
                }
                return
            }
            // the user name, plus the stored signature and its time (there is no call signature)
            let options = RequestOptions(identifyUserName: userName, signature: signature, signatureTime: signatureTime, storedSignature: true)
            let res = try await self.apiClient.post(endpoint: Endpoints.identifyUser, body: body, options: options)
            defer { finish() }
            // a reply for a user who is no longer current (reset or switched since): not applied
            guard self.withStateLock({ self.userNameState == userName }) else {
                self.logger.debug("setUserProperties reply for \"\(userName)\" ignored: no longer the current user")
                return
            }
            // its own reply handling, not identify's: no signature change, no held submits released,
            // no session restart, no "user identified"
            if case .object(let resObj) = res, case .string(let value)? = resObj["$feedbackTransactions"] {
                self.feedbackTransactions = value
                self.storage.setFeedbackTransactions(identityKey: userName, value: value)
            }
            let meta = res.toResponseMeta()
            self.sessionManager.handleResponseMeta(meta)
            // only while the ping runs (scheduleNextPing doesn't start a stopped one)
            if let pingAgainIn = meta.pingAgainIn, pingAgainIn > 0 {
                self.sessionManager.scheduleNextPing(delayMs: Int64(pingAgainIn * 1000))
            }
            await self.handleSurveyInReply(res)
        }
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

    private enum ExpiryOutcome {
        case none
        /// The app's own new signature was rejected as expired.
        case newSignatureExpired(tell: Bool)
        /// The stored signature expired: the expired state was entered.
        case enteredExpiredState
    }

    /// A 401 (HANDOVER-ON-SIGNATURE-EXPIRED §4), decided in one locked step. Only for the current user.
    /// The app's own new signature (identifyUser with `secure`, another signature than the stored one)
    /// rejected as expired: warn and tell the app, nothing else (that identify just failed). The stored
    /// signature (re-sent by setUserProperties, by an identifyUser with that same signature, or on any
    /// request), while it is still the one in use: expired when the API says so, or (no reason: an older
    /// API, or a problem the SDK can't tell apart) when setUserProperties re-sent it with a session time;
    /// then the expired state, and the app is told. Any other 401 (a wrong signature, the key, the
    /// referer) is not an expiry.
    private func onUnauthorized(_ reply: UnauthorizedReply) {
        guard let user = reply.userName else { return }
        let (outcome, release, listener): (ExpiryOutcome, AsyncSignal, SignatureExpiredListener?) = withSignatureWrite {
        let decided: (ExpiryOutcome, AsyncSignal, SignatureExpiredListener?) = withStateLock {
            // taken as the reply arrives: a stop() or re-init from here on means no callback
            let release = lifecycleRelease
            guard userNameState == user, let signature = reply.signature else { return (.none, release, nil) }
            if reply.identify == .own && signature != userSignature {
                guard reply.signatureExpiredReason else { return (.none, release, nil) }
                return (.newSignatureExpired(tell: expiryToldUsers.insert(user).inserted), release, onSignatureExpired)
            }
            let expired = reply.signatureExpiredReason || (reply.identify == .storedSignature && reply.hadSessionTime)
            // a late 401 for an older signature can't undo a fresh secure identify
            guard expired, !signatureExpired, userSignature == signature else { return (.none, release, nil) }
            signatureExpired = true
            userSignature = nil
            userSignatureTime = nil
            // the next identify (with a fresh signature) must go out, even if it matches
            identifyMemory = nil
            expiryToldUsers.insert(user)
            return (.enteredExpiredState, release, onSignatureExpired)
        }
        if case .enteredExpiredState = decided.0 { storage?.clearUserSignature(userName: user) }
        return decided
        }
        switch outcome {
        case .none:
            if userNameStateSnapshot != user { logger.debug("401 for \"\(user)\", no longer the current user: ignored") }
        case .newSignatureExpired(let tell):
            logger.warn("identifyUser(\"\(user)\") rejected: the secure signature has expired. Get a fresh signature from your server and call identifyUser() again.")
            if tell { callSignatureExpired(listener, user: user, release: release) } else if listener != nil {
                logger.debug("onSignatureExpired already called for \"\(user)\": waiting for a successful identify")
            }
        case .enteredExpiredState:
            logger.warn(
                "Secure identify signature expired (401). Call identifyUser() with a fresh signature; until then " +
                    "events, screens, ping and automatic surveys are skipped and survey submits are held."
            )
            callSignatureExpired(listener, user: user, release: release)
        }
    }

    private var userNameStateSnapshot: String? { withStateLock { userNameState } }

    /// Calls the app's onSignatureExpired for [user] after this reply is handled (an identifyUser() made
    /// from it starts from the updated state); not when stop() / clearAll() / a re-init ran or the user
    /// changed meanwhile. An error it throws is logged.
    private func callSignatureExpired(_ listener: SignatureExpiredListener?, user: String, release: AsyncSignal) {
        guard let listener else { return }
        let waitMs = timings.signatureExpiredCallDelayMs
        Task { [weak self] in
            if waitMs > 0 { try? await Task.sleep(nanoseconds: UInt64(waitMs) * 1_000_000) } else { await Task.yield() }
            guard let self else { return }
            let still = self.withStateLock { !release.isFired && self.initialized && self.userNameState == user }
            guard still else { return }
            do {
                try await listener(SignatureExpiredInfo(userName: user))
            } catch {
                self.logger.warn("onSignatureExpired threw: \(error)")
            }
        }
    }

    var isSignatureExpired: Bool { withStateLock { signatureExpired } }

    /// A stopSession() is in effect (saved across restarts until startSession() or a successful identify).
    var sessionStopped: Bool { isSessionStopped }
    var pingActive: Bool { sessionManager?.isPingActive ?? false }
    var sessionManagerForTesting: SessionManager { sessionManager }

    private enum Hold { case held, dropped, notExpired }

    /// Keeps a survey submit of [owner] until the next successful identify (in memory, at most 10,
    /// oldest dropped). Checked again under the lock: once the expired state has ended (an identify
    /// succeeded meanwhile) it isn't held but sent (`.notExpired`); a signed-in owner who is no longer
    /// current has it dropped.
    private func holdSubmit(_ params: SubmitFormRequest, owner: String?) -> Hold {
        let outcome: Hold = withStateLock {
            if !signatureExpired { return .notExpired }
            if let owner, owner != userNameState { return .dropped }
            heldSubmits.append(HeldSubmit(userName: owner, params: params))
            if heldSubmits.count > Self.maxHeldSubmits { heldSubmits.removeFirst() }
            return .held
        }
        switch outcome {
        case .held: logger.debug("submitForm held until the next identify (secure signature expired)")
        case .dropped: logger.warn("A survey submit was dropped: it belongs to \"\(owner ?? "")\", no longer the current user")
        case .notExpired: break
        }
        return outcome
    }

    /// A successful identify ends the expired state and sends any held submits, each only as the user
    /// who made it.
    private func endSignatureExpired() {
        let held: [HeldSubmit] = withStateLock {
            signatureExpired = false
            let held = heldSubmits
            heldSubmits = []
            return held
        }
        for submit in held {
            Task { await self.submitForm(submit.params, owner: submit.userName) }
        }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    public func setLocale(_ locale: String) {
        localeState = locale
        // before initialize() there is no storage yet: the value is kept in memory only
        storage?.setPreferences(locale: locale)
    }

    public func setCountry(_ country: String) {
        countryState = country
        // before initialize() there is no storage yet: the value is kept in memory only
        storage?.setPreferences(country: country)
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
        checkSurveyIdle()
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
            if let token, pendingAutoShowToken != token { return "superseded by a newer automatic show" }
            let reason: String? = {
                if rateLimited { return "rate limited" }
                if triggerType == .automatic && expired { return "secure signature expired" }
                if triggerType == .automatic && (loadingFormId != nil || isFormVisible) { return "a survey is loading or open (survey-open)" }
                if triggerType == .manual && (loadingFormId == formId || (isFormVisible && openFormIds.contains(formId))) {
                    return "that survey is already loading or open"
                }
                loadingFormId = formId
                return nil
            }()
            // the scheduled show has started: "loading" is set before "pending" ends, so a survey
            // check never sees neither
            if token != nil {
                pendingAutoShow = nil
                pendingAutoShowToken = nil
            }
            return reason
        }
        if let skipReason {
            logger.debug("show-form \(formId) skipped: \(skipReason)")
            // a scheduled show that ended here was "open" for setUserProperties until now
            if token != nil { checkSurveyIdle() }
            return
        }
        await showFormRequest(formId: formId, resetMode: resetMode, triggerType: triggerType, context: context)
        withSurveyLock { if loadingFormId == formId { loadingFormId = nil } }
        checkSurveyIdle()
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

        // who the survey is for: a signed-in user who is no longer current when the reply arrives
        // doesn't see it (a visitor's request that completes after sign-in still does)
        let requestedBy = withStateLock { userNameState }
        do {
            let res = try await apiClient.post(endpoint: Endpoints.showForm, body: .object(req))
            await applyFeedbackTransactions(res)
            let meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)

            guard case .object(let resObj) = res else { return }
            var feedbackConfigurationId: String?
            if case .string(let value)? = resObj["feedbackConfigurationId"] { feedbackConfigurationId = value }
            if let feedbackConfigurationId { pendingCtaScheduler?.cancel(formId: feedbackConfigurationId) }

            // its answers belong to that user (a custom form shown from onBeforeShowForm too)
            let stillFor: Bool = withStateLock {
                if let requestedBy, requestedBy != userNameState { return false }
                for id in [formId, feedbackConfigurationId].compactMap({ $0 }) where !id.isEmpty {
                    shownTo[id] = Owner(userName: requestedBy)
                }
                return true
            }
            guard stillFor else {
                logger.debug("survey \(formId) not shown: it was asked for \"\(requestedBy ?? "")\", no longer the current user")
                return
            }

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
        forgetShown([id])
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
        let closed: Set<String> = withSurveyLock {
            let closed = openFormIds
            isFormVisible = false
            openFormIds = []
            return closed
        }
        forgetShown(Array(closed) + [formConfigurationId])
        checkSurveyIdle()

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
        await submitForm(params, owner: ownerOf(params))
    }

    /// The user these answers belong to: who the survey was shown to, else (not found, or shown to a
    /// visitor) the current user. The survey page's submit takes it in its message handler, before the
    /// close the page sends right after it forgets who the survey was shown to.
    func ownerOf(_ params: SubmitFormRequest) -> String? {
        withStateLock { shownTo[params.formDetails.formConfigurationId].flatMap { $0.userName } ?? userNameState }
    }

    /// submitForm for answers of [owner] (nil: a visitor's, sent whoever is current). A signed-in
    /// owner's answers are sent only while they are the current user: checked now and before each send
    /// attempt (after a rate-limit wait); signing out drops them too. clearAll() while one waits drops it.
    func submitForm(_ params: SubmitFormRequest, owner: String?) async {
        guard initialized else { return }
        let generation = withStateLock { clearGeneration }
        let ownerGone: () -> Bool = { [self] in
            guard let owner else { return false }
            return withStateLock { userNameState } != owner
        }
        if ownerGone() {
            logger.warn("A survey submit was dropped: it belongs to \"\(owner ?? "")\", no longer the current user")
            return
        }
        // Secure signature expired: hold the answers until the app identifies again.
        if isSignatureExpired && holdSubmit(params, owner: owner) != .notExpired { return }
        let deviceInfo = buildDeviceInfo()
        var req: [String: JSONValue] = ["$deviceInfo": deviceInfo.toJSONValue()]
        if let triggerType = params.triggerType { req["triggerType"] = .string(triggerType) }
        req["formDetails"] = params.formDetails.toJSONValue() ?? .null

        // A submit carries the user's answers, so it is never dropped for a 429: it waits for the
        // pause to end and is retried, up to 3 attempts in all. Kept in memory only.
        for attempt in 1...Self.submitMaxAttempts {
            let wait = rateLimiter.remainingMs(.submitForm)
            if wait > 0 { try? await Task.sleep(nanoseconds: UInt64(wait) * 1_000_000) }
            guard initialized, withStateLock({ clearGeneration }) == generation else {
                logger.warn("A survey submit was dropped: the SDK was cleared or re-initialized while it waited")
                return
            }
            if ownerGone() {
                logger.warn("A survey submit was dropped: it belongs to \"\(owner ?? "")\", no longer the current user")
                return
            }
            do {
                let res = try await apiClient.post(endpoint: Endpoints.submitForm, body: .object(req))
                await applyFeedbackTransactions(res)
                sessionManager.handleResponseMeta(res.toResponseMeta())
                return
            } catch {
                let status = (error as? EncatchApiException)?.status
                if status == 429 && attempt < Self.submitMaxAttempts { continue }
                if status == 401, isSignatureExpired {
                    // held; or, if an identify ended the expired state meanwhile, tried again
                    if holdSubmit(params, owner: owner) == .notExpired {
                        if attempt < Self.submitMaxAttempts { continue }
                        logger.warn("submitForm API error: \((error as? EncatchApiException)?.message ?? "\(error)")")
                    }
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

        initialized = false
        // waiting setUserProperties updates were for the user being cleared (and a reply arriving
        // from here on calls no onSignatureExpired)
        releaseLifecycleWaits()
        storage?.clearAll()
        rateLimiter?.clear()
        // cleared last: its clear suspends, and an initialize() overlapping it must not have its new
        // state wiped by the rest of this
        let queue = retryQueue

        apiKeyState = nil
        releasePendingIdentifies()
        withStateLock {
            propsWaiting = []
            propsBusy = nil
            confirmedName = nil
            confirmedIdentity = false
            userNameState = nil
            userIdState = nil
            userSignature = nil
            userSignatureTime = nil
            signatureExpired = false
            heldSubmits = []
            expiryToldUsers = []
            onSignatureExpired = nil
            // a submit waiting out a pause is dropped; nothing shown is remembered
            shownTo = [:]
            clearGeneration += 1
            identifyMemory = nil
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
        await queue?.clear()
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
        // the next identify turns the session back on, so it must go out even if it matches
        withStateLock { identifyMemory = nil }
        isSessionPaused = false
        sessionManager?.stopPingInterval()
        cancelPendingAutoShow()
        EncatchInternalEmitter.shared.emit(.dismissForm(formConfigurationId: nil))
        let closed: Set<String> = withSurveyLock {
            let closed = openFormIds
            isFormVisible = false
            openFormIds = []
            return closed
        }
        // closed: its answers no longer need an owner (as Kotlin, both of its ids)
        forgetShown(Array(closed))
        checkSurveyIdle()
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
            confirmedIdentity = false
            confirmedName = nil
            userNameState = nil
            userIdState = nil
            userSignature = nil
            userSignatureTime = nil
            signatureExpired = false
            heldSubmits = []
            expiryToldUsers = []
            identifyMemory = nil
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
        let (ignored, closed): (Bool, Set<String>) = withSurveyLock {
            if !visible, let formId, !openFormIds.isEmpty, !openFormIds.contains(formId) { return (true, []) }
            isFormVisible = visible
            let closed = visible ? [] : openFormIds
            if !visible { openFormIds = [] }
            return (false, closed)
        }
        if ignored { logger.debug("close of \(formId ?? "") ignored: another survey is open") }
        if !visible {
            // closed (submitted, X, back, CTA, remind me later): its answers no longer need an owner
            if !ignored { forgetShown(Array(closed) + [formId]) }
            checkSurveyIdle()
        }
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
        // anything waiting for a survey to close or an identify gives up
        releaseLifecycleWaits()
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

    /// Whether the SDK has a confirmed identity (RN 1.5.0 item 2): `true` once an identify
    /// succeeded, or at launch when a user saved by an earlier run is restored with their user id.
    ///
    /// - `false` before `init`, for a visitor, and while a first identify is pending or after it failed.
    /// - Switching from one user to another keeps describing the previous user until the new user's
    ///   identify succeeds (requests already go out as a visitor during the switch).
    /// - `false` after `resetUser()` (also the server's "retries exhausted" reset) and `clearAll()`;
    ///   `stopSession()` and an expired secure signature leave it unchanged.
    /// - With `EncatchConfig.persistUserAcrossRestarts` off it is `false` at every launch until the
    ///   app identifies.
    public func isIdentified() -> Bool {
        initialized && withStateLock { confirmedIdentity }
    }
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

    /// Takes [op]'s place in line now and returns its task (to wait for it later, or not).
    func start(_ op: @escaping @Sendable () async -> Void) -> Task<Void, Never> {
        enqueue(op)
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

/// The traits as the dedupe compares them (RN SDK 1.5.0): empty operations dropped, `$unset` as a
/// sorted copy (the caller's array is never reordered); nil when nothing is left ("no traits").
func traitsForDedupe(_ traits: UserTraits?) -> JSONValue? {
    guard let traits else { return nil }
    var result: [String: JSONValue] = [:]
    if let set = traits.set, !set.isEmpty { result["$set"] = .object(set) }
    if let setOnce = traits.setOnce, !setOnce.isEmpty { result["$setOnce"] = .object(setOnce) }
    if let increment = traits.increment, !increment.isEmpty { result["$increment"] = .object(increment.mapValues { .number($0) }) }
    if let decrement = traits.decrement, !decrement.isEmpty { result["$decrement"] = .object(decrement.mapValues { .number($0) }) }
    if let unset = traits.unset, !unset.isEmpty { result["$unset"] = .array(unset.sorted().map { .string($0) }) }
    return result.isEmpty ? nil : .object(result)
}

/// Traits a request body can't carry: a number that isn't finite (JSON has no NaN or Infinity).
func hasUnsendableValue(_ traits: UserTraits?) -> Bool {
    guard let traits else { return false }
    func unsendable(_ value: JSONValue) -> Bool {
        switch value {
        case .number(let n): return !n.isFinite
        case .object(let o): return o.values.contains(where: unsendable)
        case .array(let a): return a.contains(where: unsendable)
        default: return false
        }
    }
    return (traits.set?.values.contains(where: unsendable) ?? false) ||
        (traits.setOnce?.values.contains(where: unsendable) ?? false) ||
        (traits.increment?.values.contains { !$0.isFinite } ?? false) ||
        (traits.decrement?.values.contains { !$0.isFinite } ?? false)
}

/// JSON with object keys sorted, so equal values give equal strings (identify dedupe key).
func canonicalJSONString(_ value: JSONValue) -> String {
    let encoder = JSONEncoder()
    encoder.outputFormatting = [.sortedKeys]
    guard let data = try? encoder.encode(value), let string = String(data: data, encoding: .utf8) else { return "" }
    return string
}
