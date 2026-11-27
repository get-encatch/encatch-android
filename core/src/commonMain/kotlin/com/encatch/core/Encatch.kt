package com.encatch.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Encatch Android SDK singleton. Mirrors `@encatch/react-native-sdk`'s exported
 * `Encatch` object 1:1 in method signatures and behavior — see `encatch.ts`.
 */
object Encatch {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Initialisation state
    private var initialized = false
    private var debugModeState = false

    /**
     * Debug hook: receives every completed SDK HTTP call (request + response). Only fires when
     * `EncatchConfig.debugMode` is enabled — payloads include full bodies, so they never leave
     * the SDK in production configurations (the API key header is always masked to its last 5
     * characters). Survives re-`init()` — set it once at app startup for in-app network
     * inspectors. May be invoked on any thread.
     */
    var onNetworkLog: ((EncatchNetworkLogEntry) -> Unit)? = null

    // Config
    private var apiKeyState: String? = null
    private var apiBaseUrlState: String = DEFAULT_API_BASE_URL
    private var webHostState: String = DEFAULT_WEB_HOST
    private var isFullScreenState = false
    private var onBeforeShowForm: BeforeShowFormInterceptor? = null
    @Volatile private var onSignatureExpired: SignatureExpiredListener? = null
    /**
     * Users already told through [onSignatureExpired] (guarded by [secureLock]). A rejected new
     * signature isn't told again for the same user until an identify for them succeeds, else a
     * signature the app's server keeps getting wrong would loop (callback, identify, 401, callback);
     * entering the expired state always tells, and adds the user. A successful identify removes its
     * user; resetUser(), clearAll() and a re-init clear it.
     */
    private val expiryToldUsers = mutableSetOf<String>()
    /** Tests: how long the callback waits after the reply (0: just until this reply is handled). */
    @Volatile internal var signatureExpiredCallDelayMs = 0L

    // Identity. [userIdState] is always the id of [userNameState] (or null): a request carries both
    // identity headers or neither (RN 1.4.4 item 11).
    @Volatile private var userNameState: String? = null
    @Volatile private var userIdState: String? = null
    /** After a successful secure identify: the signature and its time, sent on every request of that user. */
    @Volatile private var userSignature: String? = null
    @Volatile private var userSignatureTime: String? = null
    /**
     * The stored session-time signature expired (a 401): until the next successful identify, events,
     * screens, ping and automatic surveys are skipped and survey submits are held (RN 1.4.4 item 16).
     */
    @Volatile private var signatureExpired = false
    // A confirmed identity (RN 1.5.0 item 2): the user was restored at init with their id, or their
    // identify succeeded. Kept through a user switch until the new user's identify succeeds; cleared
    // by resetUser() (also the server's "retries exhausted" reset) and clearAll().
    @Volatile private var confirmedIdentity = false
    /** Survey submits held while the signature is expired, each with the user who made it. */
    private class HeldSubmit(val userName: String?, val params: SubmitFormRequest)
    private val heldSubmits = mutableListOf<HeldSubmit>()
    private val secureLock = Mutex()
    /** identifyUser and resetUser run one after the other (RN 1.4.4 item 13). */
    private val identityMutex = Mutex()
    // identifyUser() dedupe, for this app run only (RN SDK 1.5.0, PostHog-style): what the last
    // identify sent (or still in flight) carried. Cleared when that identify fails or is dropped, by a
    // different user's identify, resetUser, clearAll, stopSession and a signature expiry (the next
    // identify must go out; repeats still collapse). One immutable value, swapped atomically: a drop
    // reported from the retry queue can't interleave with an identify half-way through its update.
    private class IdentifyMemory(
        val name: String,
        /** The signature and its time, as sent. */
        val optionsKey: String,
        /** The fingerprint of the last traits sent for this user, and the identify that sent them. */
        val traitsKey: String?,
        val traitsToken: Any?,
        /** The locale / country the last identify for this user carried. */
        val locale: String?,
        val country: String?,
        /** The identify this memory belongs to. */
        val token: Any,
    )

    @OptIn(ExperimentalAtomicApi::class)
    private val identifyMemory = AtomicReference<IdentifyMemory?>(null)

    @OptIn(ExperimentalAtomicApi::class)
    private fun clearIdentifyMemory() = identifyMemory.store(null)

    /** A dropped identify forgets what it set: all of it while it owns the memory, else its traits. */
    @OptIn(ExperimentalAtomicApi::class)
    private fun forgetDroppedIdentify(token: Any) {
        while (true) {
            val cur = identifyMemory.load() ?: return
            val next = when {
                cur.token === token -> null
                cur.traitsToken === token ->
                    IdentifyMemory(cur.name, cur.optionsKey, null, null, cur.locale, cur.country, cur.token)
                else -> return
            }
            if (identifyMemory.compareAndSet(cur, next)) return
        }
    }

    /** A later identifyUser() with traits is sent again: setUserProperties may have changed them. */
    @OptIn(ExperimentalAtomicApi::class)
    private fun forgetIdentifyTraits() {
        while (true) {
            val cur = identifyMemory.load() ?: return
            if (cur.traitsKey == null) return
            val next = IdentifyMemory(cur.name, cur.optionsKey, null, null, cur.locale, cur.country, cur.token)
            if (identifyMemory.compareAndSet(cur, next)) return
        }
    }

    // setUserProperties (RN SDK 1.5.0) -------------------------------------------------------------
    // The user it may update: their identify succeeded, or they were restored with their id at
    // launch. Cleared on a user switch (until the new user's identify succeeds), resetUser, clearAll.
    @Volatile private var confirmedName: String? = null

    /** Identifies queued or retrying, by user name: setUserProperties waits for them. */
    @OptIn(ExperimentalAtomicApi::class)
    private val pendingIdentifies = AtomicReference<Map<String, List<CompletableDeferred<Unit>>>>(emptyMap())

    @OptIn(ExperimentalAtomicApi::class)
    private fun updatePendingIdentifies(change: (Map<String, List<CompletableDeferred<Unit>>>) -> Map<String, List<CompletableDeferred<Unit>>>) {
        while (true) {
            val cur = pendingIdentifies.load()
            if (pendingIdentifies.compareAndSet(cur, change(cur))) return
        }
    }

    /** Registers an identify of [userName] as pending; the returned function ends it (sent or dropped). */
    private fun addPendingIdentify(userName: String): () -> Unit {
        val done = CompletableDeferred<Unit>()
        updatePendingIdentifies { it + (userName to (it[userName].orEmpty() + done)) }
        return {
            done.complete(Unit)
            updatePendingIdentifies { m ->
                val rest = m[userName].orEmpty().filter { it !== done }
                if (rest.isEmpty()) m - userName else m + (userName to rest)
            }
        }
    }

    /** Ends every pending identify: the queue was emptied without reporting them (re-init). */
    @OptIn(ExperimentalAtomicApi::class)
    private fun releasePendingIdentifies() {
        pendingIdentifies.exchange(emptyMap()).values.flatten().forEach { it.complete(Unit) }
    }

    /** Completed (then replaced) by stop(), clearAll() and a re-init: waits started before then give up. */
    @OptIn(ExperimentalAtomicApi::class)
    private val lifecycleRelease = AtomicReference(CompletableDeferred<Unit>())

    @OptIn(ExperimentalAtomicApi::class)
    private fun releaseLifecycleWaits() {
        // swapped atomically: two releases at once (stop and clearAll) can't leave a deferred uncompleted
        lifecycleRelease.exchange(CompletableDeferred()).complete(Unit)
        checkSurveyIdle(release = true)
    }

    /**
     * setUserProperties calls wait their turn here, in call order, from the moment they're made: two
     * waiting at once (for a survey, or for one identify) must not go out reversed. Not [identityMutex],
     * so an identify or reset is never held up by a waiting update.
     */
    private val propsOrder = Mutex()

    // Waiting for an open survey to close: one shared watcher, a check every 500 ms that is also run
    // when a survey closes. stop() / clearAll() release every waiter.
    @OptIn(ExperimentalAtomicApi::class)
    private val surveyIdle = AtomicReference<CompletableDeferred<Unit>?>(null)

    /** How often the shared watcher checks that an open survey closed (it is also woken by a close). Tests lengthen it. */
    @Volatile internal var surveyIdlePollMs = 500L

    /** A survey is loading, on screen, or about to be shown (an automatic show waiting out its delay). */
    // pending first: the automatic show sets "loading" before it ends "pending", so reading in this
    // order (without the lock) can't miss both
    private fun isSurveyOpen(): Boolean = pendingAutoShow?.isActive == true || loadingFormId != null || isFormVisible

    private suspend fun awaitSurveyIdle(release: Deferred<Unit>) {
        while (initialized && isSurveyOpen() && !release.isCompleted) {
            val idle = surveyIdleSignal()
            select<Unit> {
                idle.onAwait {}
                release.onAwait {}
            }
        }
    }

    /** The shared signal, and its watcher when it is new (one at a time: a new one only after the last fired). */
    @OptIn(ExperimentalAtomicApi::class)
    private fun surveyIdleSignal(): CompletableDeferred<Unit> {
        while (true) {
            surveyIdle.load()?.let { return it }
            val fresh = CompletableDeferred<Unit>()
            if (surveyIdle.compareAndSet(null, fresh)) {
                scope.launch {
                    while (!fresh.isCompleted) {
                        delay(surveyIdlePollMs)
                        checkSurveyIdle()
                    }
                }
                return fresh
            }
        }
    }

    /** Wakes the survey waiters once no survey is open ([release]: wake them regardless). */
    @OptIn(ExperimentalAtomicApi::class)
    private fun checkSurveyIdle(release: Boolean = false) {
        if (!release && initialized && isSurveyOpen()) return
        surveyIdle.exchange(null)?.complete(Unit)
    }

    /**
     * setUserProperties updates go out one after the other, in call order: the next is queued only
     * when the previous one is sent or dropped, so a retried older update can't land after a newer one.
     */
    private class PropsUpdate(val release: Deferred<Unit>, val start: suspend (finish: () -> Unit) -> Unit)

    @Volatile private var propsUpdates = Channel<PropsUpdate>(Channel.UNLIMITED)
    @Volatile private var propsPump: Job? = null

    private fun startPropsPump() {
        propsPump?.cancel()
        val old = propsUpdates
        old.close()
        var dropped = 0
        while (old.tryReceive().isSuccess) dropped++
        if (dropped > 0) logger.debug("$dropped setUserProperties update(s) not sent: the SDK was re-initialized")
        val updates = Channel<PropsUpdate>(Channel.UNLIMITED)
        propsUpdates = updates
        propsPump = scope.launch {
            for (update in updates) {
                // stop() / clearAll() since it was queued: it was for the user being cleared
                if (update.release.isCompleted) continue
                val done = CompletableDeferred<Unit>()
                runCatching { update.start { done.complete(Unit) } }.onFailure { if (it is CancellationException) throw it; done.complete(Unit) }
                done.await()
            }
        }
    }

    // Preferences
    private var localeState: String? = null
    private var countryState: String? = null
    private var themeState: Theme = Theme.SYSTEM

    // Current screen
    private var currentScreen: String? = null

    // Ids
    private var deviceIdState: String? = null
    private var sessionIdState: String? = null

    // Feedback transactions
    private var feedbackTransactions: String? = null

    // Form visibility (suppresses ping). Written from the UI's main thread and the SDK's own.
    @Volatile private var isFormVisible = false

    // One survey at a time (mirrors RN 1.4.4 `_scheduleAutoShow` / Flutter 1.2.0 `_handleSurveyInReply`):
    // the automatic show waiting out its `onPageDelay`, the form whose show-form is in flight, and
    // the form on screen. Guarded by [surveyGuard] so two replies can't both pass the check.
    @Volatile private var pendingAutoShow: Job? = null
    @Volatile private var loadingFormId: String? = null
    /** The open survey, by both its names: the id asked for and the show-form reply's `feedbackConfigurationId`. */
    @Volatile private var openFormIds: Set<String> = emptySet()
    private val surveyGuard = Mutex()

    // Test seams: an HTTP engine and a settings store other than the platform's, and a shorter
    // regular ping interval (commonTest only; read at init).
    internal var engineFactory: (() -> io.ktor.client.engine.HttpClientEngine)? = null
    internal var settingsFactory: (() -> com.russhwolf.settings.Settings)? = null
    /** Tests: the logger init() uses (given the debugMode switch). */
    internal var loggerFactory: ((debugMode: () -> Boolean) -> EncatchLogger)? = null
    internal var pingIntervalMs: Long = SessionManager.PING_INTERVAL_MS

    // Session control
    private var isSessionPaused = false
    private var isSessionStopped = false

    // App version
    private var appVersionState: String = "1.0.0"
    private var appPackageName: String? = null

    // Event callbacks
    private val eventCallbacks = mutableListOf<EventCallback>()

    // Pending pre-filled responses
    private var pendingResponses: MutableMap<String, JsonElement> = mutableMapOf()

    private lateinit var storage: EncatchStorage
    // The user's data for this app run when persistUserAcrossRestarts is off; kept across a re-init.
    private val memoryUserStore = MemoryUserStore()
    private lateinit var apiClient: EncatchApiClient
    private lateinit var retryQueue: RetryQueue

    /** Tests only: how many requests the retry queue holds. */
    internal fun retryQueueSizeForTest(): Int = if (::retryQueue.isInitialized) retryQueue.queueSize() else 0
    private lateinit var sessionManager: SessionManager
    /** The 429 pause per bucket (RN 1.4.4 item 2). */
    private lateinit var rateLimiter: RateLimiter
    /** Identical trackEvent / trackScreen calls within 1 s go out once (RN 1.4.4 item 4). */
    private val collapser = CallCollapser()
    private var logger: EncatchLogger = DefaultEncatchLogger { debugModeState }

    // Tracks the retry-queue flush kicked off at the end of the most recent `init()`, and
    // serializes overlapping `init()` calls — see that method's reconfigure path.
    private var retryFlushJob: Job? = null
    private var pendingReinitJob: Job? = null

    // ============================================================================
    // Initialisation
    // ============================================================================

    @Throws(Exception::class)
    suspend fun init(apiKey: String, config: EncatchConfig? = null) {
        // Wait for any prior init() call's reconfigure to fully finish before we touch
        // anything. Without this, two overlapping calls (or a call landing while a previous
        // reconfigure's teardown is still draining) could interleave their mutations of
        // storage/apiClient/retryQueue/sessionManager — plain vars, not synchronized — which on
        // iOS's equivalent Swift implementation crashed with a pointer-authentication SIGSEGV
        // when reproduced via the tester apps' "change API key" flow.
        pendingReinitJob?.join()

        debugModeState = config?.debugMode ?: false
        logger = loggerFactory?.invoke { debugModeState } ?: DefaultEncatchLogger { debugModeState }

        if (initialized) {
            // Reconfigure rather than no-op: a host app (or this repo's tester apps switching
            // environment/API key at runtime) calling init() again expects the new
            // apiKey/config to actually take effect, not silently keep the first call's state.
            // Fully cancel-and-join the old ping loop and any still-draining retry-queue flush
            // before reassigning shared state below — see the comment above.
            logger.debug("SDK already initialized — reconfiguring with new apiKey/config")
            val oldSessionManager = sessionManager
            val oldRetryFlushJob = retryFlushJob
            val oldRetryQueue = retryQueue
            val teardown = scope.launch {
                oldSessionManager.stopPingIntervalAndJoin()
                oldRetryFlushJob?.join()
                // items in backoff or held by a pause must not go out later with the new key
                oldRetryQueue.clear()
            }
            pendingReinitJob = teardown
            teardown.join()
            // the cleared queue reports nothing: end what waits on it
            releasePendingIdentifies()
            releaseLifecycleWaits()
            rateLimiter.clear()
            // nothing of the old key's identify/secure/collapse memory carries over to the new one
            clearIdentifyMemory()
            collapser.clear()
            val dropped = secureLock.withLock {
                signatureExpired = false
                expiryToldUsers.clear()
                heldSubmits.size.also { heldSubmits.clear() }
            }
            if (dropped > 0) logger.warn("[Encatch] $dropped held survey submit(s) dropped: the SDK was re-initialized")
            isSessionPaused = false
            isSessionStopped = false
            isFormVisible = false
            cancelPendingAutoShow()
            openFormIds = emptySet()
            loadingFormId = null
        }

        apiKeyState = apiKey
        apiBaseUrlState = (config?.apiBaseUrl ?: DEFAULT_API_BASE_URL).trimEnd('/')
        webHostState = (config?.webHost ?: DEFAULT_WEB_HOST).trimEnd('/')
        isFullScreenState = config?.isFullScreen ?: false
        config?.theme?.let { themeState = it }
        onBeforeShowForm = config?.onBeforeShowForm
        onSignatureExpired = config?.onSignatureExpired

        logger.debug("Initializing SDK...")

        val settings = settingsFactory?.invoke() ?: createEncatchSettings()
        val persistUser = config?.persistUserAcrossRestarts ?: true
        storage = if (persistUser) {
            memoryUserStore.clear()
            EncatchStorage(settings)
        } else {
            // nothing about the user is kept on the device, including what an earlier run saved
            EncatchStorage(settings, userStore = memoryUserStore).also { it.deleteSavedUserData() }
        }
        val limiter = RateLimiter(scope, warn = { logger.warn(it) })
        rateLimiter = limiter
        apiClient = EncatchApiClient(
            httpClient = createHttpClient(engineFactory?.invoke() ?: createDefaultEngine()),
            baseUrlProvider = { apiBaseUrlState },
            authStateProvider = {
                AuthState(
                    apiKey = apiKeyState,
                    sessionId = sessionIdState,
                    userName = userNameState,
                    userId = userIdState,
                    userSignature = userSignature,
                    deviceId = deviceIdState,
                    appPackageName = appPackageName,
                    userSignatureTime = userSignatureTime,
                )
            },
            networkLogSink = { entry ->
                // Full request/response payloads only leave the SDK when the host explicitly
                // opted into debugMode — same gate as the debug logger.
                if (debugModeState) onNetworkLog?.invoke(entry)
            },
            onUserPendingRetryExhausted = {
                sessionManager.stopPingIntervalNow()
                resetUser()
            },
            logger = logger,
            onUnauthorized = { reply -> onUnauthorized(reply) },
            onRateLimited = { endpoint, retryAfterMs -> limiter.pauseFor(bucketOf(endpoint), retryAfterMs) },
        )
        retryQueue = RetryQueue(scope, storage, limiter, logger)
        sessionManager = SessionManager(
            scope,
            isFormVisible = { isFormVisible },
            onPing = { backgroundPing() },
            intervalMs = pingIntervalMs,
        )

        val storedName = storage.getUserName()
        deviceIdState = storage.getOrCreateDeviceId()
        sessionIdState = storage.getOrCreateSessionId()
        val prefs = storage.getPreferences()
        val facts = collectPlatformDeviceFacts()
        appPackageName = facts.appPackageName
        appVersionState = config?.appVersion ?: facts.appVersion ?: "1.0.0"

        prefs.locale?.let { localeState = it }
        prefs.country?.let { countryState = it }

        // a stopSession() from an earlier run stays in effect until startSession() or a successful identify
        isSessionStopped = storage.getSessionStopped()

        if (storedName != null) {
            // a name without its id (a device left half-identified) goes out as a visitor
            userNameState = storedName
            userIdState = storage.getUserId(storedName)
            loadStoredSignature(storedName)
            feedbackTransactions = storage.getFeedbackTransactions(storedName)
        } else {
            userNameState = null
            userIdState = null
            userSignature = null
            userSignatureTime = null
            feedbackTransactions = storage.getFeedbackTransactions("anonymous")
        }
        confirmedIdentity = userNameState != null && userIdState != null
        confirmedName = if (confirmedIdentity) userNameState else null
        startPropsPump()

        initialized = true

        val flushQueue = retryQueue
        retryFlushJob = scope.launch { flushQueue.flush() }
        pendingReinitJob = null
        logger.debug("SDK initialized. deviceId: $deviceIdState")
    }

    // ============================================================================
    // Identity
    // ============================================================================

    @Throws(Exception::class)
    suspend fun identifyUser(userName: String, traits: UserTraits? = null, options: IdentifyOptions? = null) {
        if (!initialized) return
        // one after the other with resetUser, so a reset fired just before can't undo this identify
        // and, once started, to the end: a caller cancelled half-way must not leave the memory saying
        // "sent" with nothing queued (every repeat would be skipped for the rest of the run)
        identityMutex.withLock { withContext(NonCancellable) { identifyNow(userName, traits, options) } }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun identifyNow(userName: String, traits: UserTraits?, options: IdentifyOptions?) {
        val secure = options?.secure
        // Traits a request body can't carry would fail inside the queue: refuse now.
        if (hasUnsendableValue(traits)) {
            logger.warn("identifyUser \"$userName\" ignored: traits contain a value that can't be sent (a number that isn't finite).")
            return
        }
        val attributes = traits?.toJson()

        // Dedupe (RN SDK 1.5.0). Sent: the first identify of this app run or a different user, a
        // changed signature, a locale / country that differs from what the last identify carried,
        // new traits. Not sent: the same user again with no traits, or with the same traits, as the
        // last identify sent or still in flight. A skipped call changes no memory.
        val traitsKey = traitsForDedupe(traits)?.let { stableJsonKey(it) }
        val optionsKey = stableJsonKey(
            buildJsonObject {
                put("signature", secure?.signature)
                put("signatureTime", secure?.generatedDateTimeInUtc)
            },
        )
        val memoryToken = Any()
        while (true) {
            val mem = identifyMemory.load()
            val sameUser = mem != null && mem.name == userName
            val duplicate = mem != null && sameUser && optionsKey == mem.optionsKey &&
                (options?.locale == null || options.locale == mem.locale) &&
                (options?.country == null || options.country == mem.country) &&
                (traitsKey == null || traitsKey == mem.traitsKey)
            if (duplicate) {
                logger.debug(
                    "identifyUser \"$userName\" skipped: same user and traits as the last identify in this app run. " +
                        "To update properties again on purpose (for example a repeated \$increment), use setUserProperties(\"$userName\", traits).",
                )
                return
            }
            // another user starts with no traits, locale or country remembered
            val kept = if (sameUser) mem else null
            val next = IdentifyMemory(
                name = userName,
                optionsKey = optionsKey,
                traitsKey = traitsKey ?: kept?.traitsKey,
                traitsToken = if (traitsKey != null) memoryToken else kept?.traitsToken,
                locale = options?.locale ?: kept?.locale,
                country = options?.country ?: kept?.country,
                token = memoryToken,
            )
            // cleared meanwhile (a signature expiry, stopSession): decide again on what's there now
            if (identifyMemory.compareAndSet(mem, next)) break
        }
        var pendingFinish: (() -> Unit)? = null
        // once the memory says "sent", the identify must be queued: if anything below fails, forget it
        try {

            // Switching user: take this user's own stored id (or none) with the name, so a request never
            // pairs this name with another user's id; without one, requests go as a visitor until the
            // reply. The previous user's signature is not carried over.
            if (userName != userNameState) {
                // a queued identify of the previous user is not sent any more
                retryQueue.removeQueued("identifyUser")
                // nor are the previous user's held survey answers, and their expired signature ends here
                // (never send one user's answers as another's)
                // under the expiry lock, so a 401 for the previous user can't land half-way and pause
                // (or drop the signature of) the new one
                val dropped = secureLock.withLock {
                    signatureExpired = false
                    // not confirmed until this user's identify succeeds (setUserProperties waits for it)
                    confirmedName = null
                    userIdState = null
                    userNameState = userName
                    userIdState = storage.getUserId(userName)
                    loadStoredSignature(userName)
                    heldSubmits.size.also { heldSubmits.clear() }
                }
                if (dropped > 0) logger.warn("[Encatch] $dropped held survey submit(s) dropped: another user was identified")
            }
            storage.setUserName(userName)

            options?.locale?.let {
                localeState = it
                storage.setPreferences(locale = it)
            }
            options?.country?.let {
                countryState = it
                storage.setPreferences(country = it)
            }

            val deviceInfo = buildDeviceInfo()
            val req = buildJsonObject {
                put("userName", userName)
                userIdState?.let { put("userId", it) }
                secure?.signature?.let { put("userSignature", it) }
                put("\$deviceInfo", deviceInfo.toJson())
                attributes?.let { put("userAttributes", it) }
                feedbackTransactions?.let { put("\$feedbackTransactions", it) }
            }
            val auth = IdentifyAuth(userName, secure?.signature, secure?.generatedDateTimeInUtc)

            // While tracking is paused after a 429, hold only the latest identify: it goes out when the
            // pause ends, so a sign-in during a pause isn't lost and repeated calls don't pile up.
            if (rateLimiter.isPaused(RateBucket.TRACKING)) retryQueue.removeQueued("identifyUser")

            suspend fun sendIdentify() {
                val res = apiClient.send(Endpoints.IDENTIFY_USER, req, identify = auth)
                // a reply for a user who is no longer current (reset or switched since): not applied
                if (userNameState != userName) {
                    logger.debug("identify reply for \"$userName\" ignored: no longer the current user")
                    return
                }
                res["userId"]?.jsonPrimitive?.contentOrNull?.let {
                    userIdState = it
                    storage.setUserId(userName, it)
                }
                // the identify succeeded: this user is now the confirmed identity
                if (userIdState != null) {
                    confirmedIdentity = true
                    confirmedName = userName
                }
                // Secure identify: from now on the signature (and its time) go on every request of this
                // user and survive a restart; an identify without `secure` clears them.
                // under the expiry lock: a late 401 for the old signature then sees it replaced
                secureLock.withLock {
                    userSignature = secure?.signature
                    userSignatureTime = secure?.generatedDateTimeInUtc
                }
                if (secure != null) {
                    storage.setUserSignature(userName, StoredSignature(secure.signature, secure.generatedDateTimeInUtc))
                } else {
                    storage.clearUserSignature(userName)
                }
                endSignatureExpired()
                secureLock.withLock { expiryToldUsers.remove(userName) }
                res["\$feedbackTransactions"]?.jsonPrimitive?.contentOrNull?.let {
                    feedbackTransactions = it
                    storage.setFeedbackTransactions(userName, it)
                }
                val meta = res.toResponseMeta()
                sessionManager.handleResponseMeta(meta)

                EncatchInternalEmitter.emit(InternalEvent.UserIdentified(userNameState, userIdState))

                startSession(StartSessionOptions(skipImmediatePing = true, skipImmediateTrackScreen = true))

                if (meta.pingAgainIn != null && meta.pingAgainIn > 0) {
                    sessionManager.scheduleNextPing((meta.pingAgainIn * 1000).toLong())
                }

                handleSurveyInReply(res)
            }

            // setUserProperties for this user waits until this identify is sent or dropped
            val finishPending = addPendingIdentify(userName).also { pendingFinish = it }
            retryQueue.enqueue(
                "identifyUser",
                // dropped without succeeding: the same identify may be sent again (its traits too, even
                // if a later identify without traits took over the rest of the memory)
                onDrop = {
                    finishPending()
                    forgetDroppedIdentify(memoryToken)
                },
            ) {
                sendIdentify()
                finishPending()
            }
        } catch (err: Throwable) {
            forgetDroppedIdentify(memoryToken)
            pendingFinish?.invoke()
            throw err
        }
    }

    // ============================================================================
    // setUserProperties
    // ============================================================================

    /**
     * Updates the identified user's properties, with the same operations as identifyUser's traits
     * (`$set`, `$setOnce`, `$increment`, `$decrement`, `$unset`), through identify-user. Never
     * deduped: every call is sent.
     *
     * [userName] must be the current user, and their identify must have succeeded (or they were
     * restored with their id at launch); otherwise the call is ignored, so a pending or failed switch
     * can't update the wrong person. Waits for an identify of that user still in flight, and for an
     * open survey to close. Returns once the update is queued or ignored.
     *
     * It counts against the identify-user rate limit (about 5 per minute per user; a 429 pauses all
     * tracking). An update with `$increment` or `$decrement` is sent once, never retried, so a timeout
     * can't apply it twice.
     */
    @OptIn(ExperimentalAtomicApi::class)
    @Throws(Exception::class)
    suspend fun setUserProperties(userName: String, traits: UserTraits) {
        if (!initialized) return
        if (hasUnsendableValue(traits)) {
            logger.warn("setUserProperties(\"$userName\") ignored: traits contain a value that can't be sent (a number that isn't finite).")
            return
        }
        if (traitsForDedupe(traits) == null) {
            logger.debug("setUserProperties ignored: no traits to set.")
            return
        }
        // what will be sent, taken now: the caller may change their maps meanwhile
        val wire = traits.toJson()
        val counts = !traits.increment.isNullOrEmpty() || !traits.decrement.isNullOrEmpty()

        // Identity calls made before this one run first; then wait, outside the identity lock (so a
        // resetUser() isn't held up), for an identify of this user still in flight and for an open
        // survey to close.
        val release = lifecycleRelease.load()
        propsOrder.withLock {
            while (true) {
                val pending = identityMutex.withLock { pendingIdentifies.loadPending(userName) }
                when {
                    pending.isNotEmpty() -> for (identify in pending) {
                        select<Unit> {
                            identify.onAwait {}
                            release.onAwait {}
                        }
                        if (release.isCompleted) break
                    }
                    isSurveyOpen() -> awaitSurveyIdle(release)
                    else -> break
                }
                if (release.isCompleted || !initialized) return
            }
            identityMutex.withLock { withContext(NonCancellable) { setUserPropertiesNow(userName, wire, counts, release) } }
        }
    }

    @OptIn(ExperimentalAtomicApi::class)
    private fun AtomicReference<Map<String, List<CompletableDeferred<Unit>>>>.loadPending(userName: String) =
        load()[userName].orEmpty()

    private suspend fun setUserPropertiesNow(userName: String, wire: JsonObject, counts: Boolean, release: Deferred<Unit>) {
        if (!initialized || release.isCompleted) return
        val current = userNameState
        if (current == null) {
            logger.debug("setUserProperties ignored: no user is identified. Call identifyUser() first.")
            return
        }
        if (current != userName) {
            logger.warn("setUserProperties(\"$userName\") ignored: the current user is \"$current\".")
            return
        }
        val userId = userIdState
        if (userId == null || confirmedName != userName) {
            logger.warn("setUserProperties(\"$userName\") ignored: \"$userName\" isn't identified (identifyUser failed or was never called).")
            return
        }
        if (signatureExpired) {
            logger.debug("setUserProperties(\"$userName\") ignored: secure signature expired, waiting for identify")
            return
        }
        if (rateLimiter.isPaused(RateBucket.TRACKING)) {
            logger.warn("setUserProperties ignored: rate limited, try again later.")
            return
        }

        // a later identifyUser() with traits is sent again (the properties may have changed since)
        forgetIdentifyTraits()

        val req = buildJsonObject {
            put("userName", userName)
            put("userId", userId)
            put("\$deviceInfo", buildDeviceInfo().toJson())
            put("userAttributes", wire)
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }
        propsUpdates.trySend(PropsUpdate(release) { finish -> queueUserProperties(userName, userId, req, counts, release, finish) })
    }

    private suspend fun queueUserProperties(
        userName: String,
        userId: String,
        req: JsonObject,
        counts: Boolean,
        release: Deferred<Unit>,
        finish: () -> Unit,
    ) {
        retryQueue.enqueue(
            "setUserProperties",
            maxRetries = if (counts) 1 else RetryQueue.MAX_RETRIES,
            onDrop = finish,
        ) send@{
            // checked again just before sending: a reset or switch may have happened
            if (release.isCompleted || userNameState != userName || confirmedName != userName || userIdState != userId) {
                logger.debug("setUserProperties(\"$userName\") not sent: the user changed")
                finish()
                return@send
            }
            if (signatureExpired) {
                logger.debug("setUserProperties(\"$userName\") ignored: secure signature expired, waiting for identify")
                finish()
                return@send
            }
            // a survey opened meanwhile: send after it closes (an identify-user while a survey is
            // open can drop it from the person's candidates on the server, so its submit fails)
            if (isSurveyOpen()) {
                scope.launch {
                    try {
                        awaitSurveyIdle(release)
                        if (release.isCompleted) finish() else queueUserProperties(userName, userId, req, counts, release, finish)
                    } catch (err: Throwable) {
                        // never leave the next update waiting behind this one
                        finish()
                        if (err !is CancellationException) logger.warn("setUserProperties not queued again: ${err.message}")
                    }
                }
                return@send
            }
            // the user name, plus the stored signature and its time (not the call's: there is none)
            val auth = IdentifyAuth(userName, userSignature, userSignatureTime)
            val res = apiClient.send(Endpoints.IDENTIFY_USER, req, identify = auth, storedSignature = true)
            try {
                // a reply for a user who is no longer current (reset or switched since): not applied
                if (userNameState != userName) {
                    logger.debug("setUserProperties reply for \"$userName\" ignored: no longer the current user")
                    return@send
                }
                // its own reply handling, not identify's: no signature change, no held submits
                // released, no session restart, no "user identified"
                res["\$feedbackTransactions"]?.jsonPrimitive?.contentOrNull?.let {
                    feedbackTransactions = it
                    storage.setFeedbackTransactions(userName, it)
                }
                val meta = res.toResponseMeta()
                sessionManager.handleResponseMeta(meta)
                // only while the ping runs (scheduleNextPing doesn't start a stopped one)
                if (meta.pingAgainIn != null && meta.pingAgainIn > 0) {
                    sessionManager.scheduleNextPing((meta.pingAgainIn * 1000).toLong())
                }
                handleSurveyInReply(res)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                // sent: never sent again for a local failure
                logger.warn("setUserProperties reply not applied: ${err.message}")
            } finally {
                finish()
            }
        }
    }

    private fun loadStoredSignature(userName: String) {
        val stored = storage.getUserSignature(userName)
        userSignature = stored?.signature
        userSignatureTime = stored?.time
    }

    /**
     * A 401 (HANDOVER-ON-SIGNATURE-EXPIRED §4). Only for the current user. The app's own new signature
     * (identifyUser with `secure`, another signature than the stored one) rejected as expired: warn and
     * tell the app, nothing else (that identify just failed). The stored signature (re-sent by
     * setUserProperties, by an identifyUser with that same signature, or on any request), while it is
     * still the one in use: expired when the API says so, or (no reason: an older API, or a problem
     * the SDK can't tell apart) when setUserProperties re-sent it with a session time; then the
     * expired state, and the app is told. Any other 401 (a wrong signature, the key, the referer) is
     * not an expiry.
     */
    @OptIn(ExperimentalAtomicApi::class)
    private suspend fun onUnauthorized(reply: UnauthorizedReply) {
        // taken as the reply arrives: a stop() or re-init from here on means no callback
        val release = lifecycleRelease.load()
        // once started, to the end: a cancelled requester must not leave the state entered untold
        withContext(NonCancellable) { checkSignatureExpiry(reply, release) }
    }

    private suspend fun checkSignatureExpiry(reply: UnauthorizedReply, release: Deferred<Unit>) {
        val user = reply.userName ?: return
        if (user != userNameState) {
            logger.debug("401 for \"$user\", no longer the current user: ignored")
            return
        }
        val signature = reply.signature ?: return
        if (reply.identify == UnauthorizedReply.Identify.OWN && signature != userSignature) {
            if (!reply.signatureExpiredReason) return
            logger.warn(
                "[Encatch] identifyUser(\"$user\") rejected: the secure signature has expired. " +
                    "Get a fresh signature from your server and call identifyUser() again.",
            )
            notifySignatureExpired(user, enteredExpiredState = false, release)
            return
        }
        val expired = reply.signatureExpiredReason ||
            (reply.identify == UnauthorizedReply.Identify.STORED_SIGNATURE && reply.hadSessionTime)
        if (expired && enterSignatureExpired(user, signature)) notifySignatureExpired(user, enteredExpiredState = true, release)
    }

    /**
     * The stored signature expired: warn once and wait for a new identify. A late 401 for an older
     * signature, or for a user no longer current, is ignored — it must not undo a fresh secure
     * identify. True when the expired state was entered.
     */
    private suspend fun enterSignatureExpired(userName: String, signature: String): Boolean {
        secureLock.withLock {
            if (signatureExpired) return false
            if (userName != userNameState || signature != userSignature) {
                logger.debug("401 for an older signature or another user: ignored")
                return false
            }
            signatureExpired = true
            // the next identify (with a fresh signature) must go out, even if it matches
            clearIdentifyMemory()
            userSignature = null
            userSignatureTime = null
            storage.clearUserSignature(userName)
        }
        logger.warn(
            "[Encatch] Secure identify signature expired (401). Call identifyUser() with a fresh signature; " +
                "until then events, screens, ping and automatic surveys are skipped and survey submits are held.",
        )
        return true
    }

    /**
     * Calls [EncatchConfig.onSignatureExpired] for [userName]: always when the expired state was just
     * entered, else (a rejected new signature) only if they weren't told since their last successful
     * identify. After this reply is handled (an identifyUser() made from it starts from the updated
     * state), and not when stop() / clearAll() / a re-init ran or the user changed meanwhile. What it
     * throws is logged and ignored.
     */
    private suspend fun notifySignatureExpired(userName: String, enteredExpiredState: Boolean, release: Deferred<Unit>) {
        val tell = secureLock.withLock { expiryToldUsers.add(userName) || enteredExpiredState }
        val listener = onSignatureExpired ?: return
        if (!tell) {
            logger.debug("onSignatureExpired already called for \"$userName\": waiting for a successful identify")
            return
        }
        scope.launch {
            val wait = signatureExpiredCallDelayMs
            if (wait > 0) delay(wait) else yield()
            if (release.isCompleted || !initialized || userNameState != userName) return@launch
            try {
                listener(SignatureExpiredInfo(userName))
            } catch (err: Throwable) {
                logger.warn("[Encatch] onSignatureExpired threw: ${err.message ?: err}")
            }
        }
    }

    /**
     * Keeps a survey submit made by [madeBy] until the next successful identify (in memory, the latest
     * [MAX_HELD_SUBMITS]). Only while [madeBy] is still the current user: a submit whose 401 arrives
     * after a switch must never be held for, and later sent as, the next user.
     */
    private suspend fun holdSubmit(params: SubmitFormRequest, madeBy: String?) {
        val held = secureLock.withLock {
            (madeBy == userNameState).also { current ->
                if (current) {
                    heldSubmits.add(HeldSubmit(madeBy, params))
                    if (heldSubmits.size > MAX_HELD_SUBMITS) heldSubmits.removeAt(0)
                }
            }
        }
        if (held) logger.debug("submitForm held until the next identify (secure signature expired)")
        else logger.warn("[Encatch] A survey submit was dropped: it was made by another user than the current one")
    }

    /** A successful identify ends the expired state and sends the held submits, each only as the user who made it. */
    private suspend fun endSignatureExpired() {
        val held = secureLock.withLock {
            signatureExpired = false
            heldSubmits.toList().also { heldSubmits.clear() }
        }
        held.forEach { submit ->
            scope.launch {
                if (submit.userName == userNameState) {
                    submitForm(submit.params)
                } else {
                    logger.warn("[Encatch] A held survey submit was dropped: it was made by another user than the current one")
                }
            }
        }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    fun setLocale(locale: String) {
        localeState = locale
        // saved now, not in the background: a later save (identifyUser, setCountry, another
        // setLocale) can't be overwritten by this one finishing after it
        if (::storage.isInitialized) storage.setPreferences(locale = locale)
    }

    fun setCountry(country: String) {
        countryState = country
        // saved now, as setLocale is
        if (::storage.isInitialized) storage.setPreferences(country = country)
    }

    fun setTheme(theme: Theme) {
        themeState = theme
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    @Throws(Exception::class)
    suspend fun trackEvent(eventName: String) {
        if (!initialized || isFullScreenState) return
        if (rateLimiter.isPaused(RateBucket.TRACKING)) {
            logger.debug("trackEvent \"$eventName\" skipped: rate limited")
            return
        }
        if (signatureExpired) {
            logger.debug("trackEvent \"$eventName\" skipped: secure signature expired, waiting for identify")
            return
        }
        if (!collapser.admitEvent(eventName)) {
            logger.debug("trackEvent \"$eventName\" collapsed (same event within ${COLLAPSE_WINDOW_MS} ms)")
            return
        }

        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            put("eventName", eventName)
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }

        retryQueue.enqueue("trackEvent") {
            val res = apiClient.post(Endpoints.TRACK_EVENT, req)
            applyFeedbackTransactions(res)
            val meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)
            handleSurveyInReply(res)
        }
    }

    /**
     * Best-effort call for form lifecycle events; not enqueued/retried, matches `_trackFormEvent`.
     * Public so the `:android` UI module's WebView bridge can call it for form:started/answered/show,
     * mirroring how the RN SDK's `useEncatchFormWebView` hook calls `Encatch._trackFormEvent`.
     */
    @Throws(Exception::class)
    suspend fun trackFormEvent(eventName: String, feedbackConfigurationId: String?) {
        if (!initialized) return
        // survey events are an identified user's: the server answers 403 to a visitor's (RN 1.4.4 item 8)
        if (rateLimiter.isPaused(RateBucket.TRACKING)) {
            logger.debug("survey event $eventName skipped: rate limited")
            return
        }
        if (signatureExpired) {
            logger.debug("survey event $eventName skipped: secure signature expired")
            return
        }
        if (!hasIdentity) {
            logger.debug("survey event $eventName skipped: visitor")
            return
        }
        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            put("eventName", eventName)
            feedbackConfigurationId?.let { put("feedbackConfigurationId", it) }
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }
        runCatching {
            val res = apiClient.post(Endpoints.TRACK_EVENT, req)
            applyFeedbackTransactions(res)
            val meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)
            handleSurveyInReply(res)
        }
    }

    @Throws(Exception::class)
    suspend fun trackScreen(screenName: String) {
        if (!initialized || isFullScreenState) return

        // the current screen is remembered even when nothing is sent (it goes with the next ping)
        currentScreen = screenName
        if (rateLimiter.isPaused(RateBucket.TRACKING)) {
            logger.debug("trackScreen \"$screenName\" skipped: rate limited")
            return
        }
        if (signatureExpired) {
            logger.debug("trackScreen \"$screenName\" skipped: secure signature expired, waiting for identify")
            return
        }
        // only the same screen as the last one tracked collapses (A -> B -> A is sent in full)
        if (!collapser.admitScreen(screenName)) {
            logger.debug("trackScreen \"$screenName\" collapsed (same screen within ${COLLAPSE_WINDOW_MS} ms)")
            return
        }
        val deviceInfo = buildDeviceInfo(screenName)
        val req = buildJsonObject {
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }

        retryQueue.enqueue("trackScreen") {
            val res = apiClient.post(Endpoints.TRACK_SCREEN, req)
            applyFeedbackTransactions(res)
            val meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)
            handleSurveyInReply(res)
        }
    }

    // ============================================================================
    // Form display
    // ============================================================================

    @Throws(Exception::class)
    suspend fun showForm(formId: String, options: ShowFormOptions? = null) {
        if (!initialized) return
        showFormInternal(formId, options?.reset ?: ResetMode.ALWAYS, TriggerType.MANUAL, options?.context)
    }

    /**
     * A survey the server offers in a reply — identify, ping, track-event, track-screen and survey
     * events alike: `nextFeedbackId`, shown after `onPageDelay` seconds; without it, `formConfigurationId`
     * (older servers; none send it today) is shown at once. Mirrors RN 1.4.4 `_handleSurveyInReply` and the
     * web SDK's reply hook; before this only track-screen replies were read, so surveys launched by
     * an event, on identify or in a ping never opened.
     */
    internal suspend fun handleSurveyInReply(res: JsonObject) {
        val next = res["nextFeedbackId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        val legacy = res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        // as RN 1.4.4: the server's `nextFeedbackId` first; the legacy field (shown at once) only without it
        val formId = next ?: legacy ?: return
        val delayMs = if (next != null) ((res["onPageDelay"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong().coerceAtLeast(0L) else 0L
        scheduleAutoShow(formId, delayMs)
    }

    /** One pending automatic show at a time; none while a survey is pending, loading or open. */
    private suspend fun scheduleAutoShow(formId: String, delayMs: Long) {
        if (signatureExpired) {
            logger.debug("survey $formId offered: skipped (secure signature expired)")
            return
        }
        surveyGuard.withLock {
            if (pendingAutoShow?.isActive == true || loadingFormId != null || isFormVisible) {
                logger.debug("survey $formId offered while another is pending or open: skipped (survey-open)")
                return
            }
            pendingAutoShow = scope.launch {
                if (delayMs > 0) delay(delayMs)
                runCatching { showFormInternal(formId, ResetMode.ALWAYS, TriggerType.AUTOMATIC, null, fromSchedule = true) }
            }
        }
    }

    /** Cancels a survey waiting out its delay (stopSession, resetUser, clearAll). */
    private fun cancelPendingAutoShow() {
        pendingAutoShow?.cancel()
        pendingAutoShow = null
        checkSurveyIdle()
    }

    private suspend fun showFormInternal(
        formId: String,
        resetMode: ResetMode,
        triggerType: TriggerType,
        context: Map<String, ContextValue>?,
        fromSchedule: Boolean = false,
    ) {
        if (rateLimiter.isPaused(RateBucket.SHOW_FORM)) {
            if (fromSchedule) {
                val self = currentCoroutineContext()[Job]
                surveyGuard.withLock { if (pendingAutoShow === self) pendingAutoShow = null }
                checkSurveyIdle()
            }
            logger.debug("showForm $formId skipped: rate limited")
            return
        }
        val proceed = surveyGuard.withLock {
            // the scheduled show has started: it is now "loading", no longer "pending" — if it is still the current one
            if (fromSchedule && pendingAutoShow !== currentCoroutineContext()[Job]) return@withLock false
            val go = when {
                // an automatic show never interrupts a survey that is loading or open
                triggerType == TriggerType.AUTOMATIC && (signatureExpired || loadingFormId != null || isFormVisible) -> false
                // asking again for the survey already loading or open keeps the answers in progress
                triggerType == TriggerType.MANUAL && (loadingFormId == formId || (isFormVisible && formId in openFormIds)) -> false
                else -> {
                    loadingFormId = formId
                    true
                }
            }
            // "loading" is set before "pending" ends, so a survey check never sees neither
            if (fromSchedule) pendingAutoShow = null
            go
        }
        if (!proceed) {
            logger.debug("show-form $formId skipped: a survey is loading or open (survey-open)")
            // a scheduled show that ended here was "open" for setUserProperties until now
            if (fromSchedule) checkSurveyIdle()
            return
        }
        try {
            showFormRequest(formId, resetMode, triggerType, context)
        } finally {
            // even when cancelled: a guard left "loading" would block every automatic survey after it
            withContext(NonCancellable) { surveyGuard.withLock { if (loadingFormId == formId) loadingFormId = null } }
            checkSurveyIdle()
        }
    }

    private suspend fun showFormRequest(
        formId: String,
        resetMode: ResetMode,
        triggerType: TriggerType,
        context: Map<String, ContextValue>?,
    ) {
        pendingCtaScheduler?.cancel(formId)

        val serializedContext: JsonObject? = context?.let { ctx ->
            buildJsonObject { ctx.forEach { (k, v) -> put(k, v.toJson()) } }
        }

        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            put("formSlugOrId", formId)
            put("triggerType", triggerType.wireValue)
            localeState?.let { put("language", it) }
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }

        runCatching {
            val res = apiClient.post(Endpoints.SHOW_FORM, req)
            applyFeedbackTransactions(res)
            val meta = res.toResponseMeta()
            sessionManager.handleResponseMeta(meta)

            val feedbackConfigurationId = res["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull
            feedbackConfigurationId?.let { pendingCtaScheduler?.cancel(it) }

            val prefill = pendingResponsesJson()

            val allow = onBeforeShowForm?.invoke(
                ShowFormInterceptorPayload(
                    formId = formId,
                    formConfig = res.toShowFormResponse(),
                    resetMode = resetMode,
                    triggerType = triggerType,
                    prefillResponses = prefill,
                    locale = localeState,
                    theme = themeState,
                    context = serializedContext,
                ),
            ) ?: true

            if (!allow) {
                clearPendingResponses()
                return@runCatching
            }

            val target = InlineSlotRegistry.resolvePresentationTarget(formId, feedbackConfigurationId)

            EncatchInternalEmitter.emit(
                InternalEvent.ShowForm(
                    ShowFormPayload(
                        formId = formId,
                        formConfig = res.toShowFormResponse(),
                        resetMode = resetMode,
                        triggerType = triggerType,
                        prefillResponses = prefill,
                        locale = localeState,
                        theme = themeState,
                        context = serializedContext,
                        presentation = if (target is PresentationTarget.Inline) "inline" else "modal",
                        inlineSlotId = (target as? PresentationTarget.Inline)?.slotId,
                    ),
                ),
            )

            isFormVisible = true
            openFormIds = setOfNotNull(formId, feedbackConfigurationId?.takeIf { it.isNotEmpty() })
        }.onFailure {
            if (it is CancellationException) throw it
            // 404: "not eligible now" (the server has no "already shown") — expected, no retry
            if (it is EncatchApiException && it.status == 404) logger.debug("show-form $formId: not eligible now (404)")
            else logger.warn("showForm API error: ${it.message}")
        }
    }

    /** Both identity headers go out: a user name and the id the server gave it. */
    internal val hasIdentity: Boolean get() = userNameState != null && userIdState != null

    /**
     * A survey the user closed before its final submit (the survey page's ✕, a tap outside it, the
     * back button): the `form:dismissed` survey event (identified users) and `dismiss-form` with the
     * form's UUID from the show-form reply — for visitors too, as the web SDK and Flutter 1.2.0 do,
     * so a survey that stops on "Dismissed" is not offered again. The UI closes itself.
     */
    internal suspend fun recordDismissal(formConfigurationId: String?) {
        if (!initialized) return
        val id = formConfigurationId?.takeIf { it.isNotEmpty() }
        scope.launch { runCatching { trackFormEvent("form:dismissed", id) } }
        if (id != null) {
            val req = buildJsonObject {
                put("formConfigurationId", id)
                put("\$deviceInfo", buildDeviceInfo().toJson())
                feedbackTransactions?.let { put("\$feedbackTransactions", it) }
            }
            runCatching {
                val res = apiClient.post(Endpoints.DISMISS_FORM, req)
                applyFeedbackTransactions(res)
                sessionManager.handleResponseMeta(res.toResponseMeta())
            }.onFailure { if (it is CancellationException) throw it else logger.warn("dismissForm API error: ${it.message}") }
        } else {
            // dismiss-form needs the form's UUID (400 otherwise)
            logger.warn("survey closed without a form id: no dismiss-form sent")
        }
        emitEvent(EventType.FORM_DISMISSED, EventPayload(formId = id ?: "", timestamp = currentTimeMillis()))
    }

    @Throws(Exception::class)
    suspend fun dismissForm(formConfigurationId: String? = null) {
        if (!initialized) return

        pendingCtaScheduler?.cancel(formConfigurationId)
        EncatchInternalEmitter.emit(InternalEvent.DismissForm(formConfigurationId))
        isFormVisible = false
        openFormIds = emptySet()
        checkSurveyIdle()

        scope.launch { trackFormEvent("form:dismissed", formConfigurationId) }

        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            formConfigurationId?.let { put("formConfigurationId", it) }
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }

        runCatching {
            val res = apiClient.post(Endpoints.DISMISS_FORM, req)
            applyFeedbackTransactions(res)
            sessionManager.handleResponseMeta(res.toResponseMeta())
        }

        emitEvent(EventType.FORM_DISMISSED, EventPayload(formId = formConfigurationId ?: "", timestamp = currentTimeMillis()))
    }

    // ============================================================================
    // Form response helpers
    // ============================================================================

    fun addToResponse(questionId: String, value: Any?) {
        pendingResponses[questionId] = anyToJsonElement(value)
    }

    fun getPendingResponses(): Map<String, Any?> = pendingResponses.mapValues { jsonElementToAny(it.value) }

    fun clearPendingResponses() {
        pendingResponses = mutableMapOf()
    }

    private fun pendingResponsesJson(): Map<String, JsonElement> = pendingResponses.toMap()

    // ============================================================================
    // Submit form
    // ============================================================================

    @Throws(Exception::class)
    suspend fun submitForm(params: SubmitFormRequest) {
        if (!initialized) return
        // the user these answers belong to: they are never held for, or sent as, another
        val madeBy = userNameState
        // secure signature expired: the answers wait for the next identify
        if (signatureExpired) {
            holdSubmit(params, madeBy)
            return
        }
        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            params.triggerType?.let { put("triggerType", it) }
            put("formDetails", EncatchJson.encodeToJsonElement(FormDetails.serializer(), params.formDetails))
            put("\$deviceInfo", deviceInfo.toJson())
        }
        // A submit carries the user's answers, so it is never dropped for a 429: it waits for the
        // pause to end and is retried, up to SUBMIT_MAX_ATTEMPTS in all (in memory only).
        for (attempt in 1..SUBMIT_MAX_ATTEMPTS) {
            val wait = rateLimiter.remainingMs(RateBucket.SUBMIT_FORM)
            if (wait > 0) delay(wait)
            try {
                val res = apiClient.post(Endpoints.SUBMIT_FORM, req)
                applyFeedbackTransactions(res)
                sessionManager.handleResponseMeta(res.toResponseMeta())
                return
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (err: Throwable) {
                val status = (err as? EncatchApiException)?.status
                if (status == 429 && attempt < SUBMIT_MAX_ATTEMPTS) continue
                if (status == 401 && signatureExpired) holdSubmit(params, madeBy)
                else logger.warn("submitForm API error: ${err.message}")
                return
            }
        }
    }

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

    @Throws(Exception::class)
    suspend fun refineText(params: RefineTextRequest): RefineTextResponse {
        if (!initialized) throw EncatchNotInitializedException()
        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            put("questionId", params.questionId)
            put("feedbackConfigurationId", params.feedbackConfigurationId)
            put("userText", params.userText)
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }
        val res = apiClient.post(Endpoints.REFINE_TEXT, req)
        applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())
        return RefineTextResponse(
            message = res["message"]?.jsonPrimitive?.contentOrNull,
            refinedText = res["refinedText"]?.jsonPrimitive?.contentOrNull,
            status = res["status"]?.jsonPrimitive?.doubleOrNull?.toInt(),
            error = res["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    @Throws(Exception::class)
    suspend fun streamQnaWithAi(params: QnaWithAiRequest, onChunk: (String) -> Unit, onDone: (String) -> Unit) {
        if (!initialized) throw EncatchNotInitializedException()
        require(params.feedbackConfigurationId.isNotEmpty() && params.questionId.isNotEmpty() && params.conversation.isNotEmpty()) {
            "[Encatch] feedbackConfigurationId, questionId, and conversation are required for qna-with-ai stream"
        }
        apiClient.streamQnaWithAi(params.feedbackConfigurationId, params.questionId, params.conversation, onChunk, onDone)
    }

    @Throws(Exception::class)
    suspend fun uploadFile(params: UploadFileRequest): UploadFileResponse {
        if (apiKeyState == null) throw EncatchNotInitializedException()
        val bytes = when (val src = params.file) {
            is UploadFileSource.Bytes -> src.bytes
            is UploadFileSource.ContentUri -> readContentUri(src.uri)
        }
        val mimeType = when (val src = params.file) {
            is UploadFileSource.Bytes -> src.mimeType
            is UploadFileSource.ContentUri -> src.mimeType
        }
        return apiClient.uploadFile(
            feedbackConfigurationId = params.feedbackConfigurationId,
            questionId = params.questionId,
            fileBytes = bytes,
            fileName = params.fileName,
            mimeType = mimeType,
            onProgress = params.onProgress,
        )
    }

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    @Throws(Exception::class)
    suspend fun clearAll() {
        if (::sessionManager.isInitialized) sessionManager.stopPingIntervalNow()
        cancelPendingAutoShow()
        clearIdentifyMemory()
        collapser.clear()
        if (::rateLimiter.isInitialized) rateLimiter.clear()

        EncatchInternalEmitter.emit(InternalEvent.DismissForm())
        isFormVisible = false
        openFormIds = emptySet()
        loadingFormId = null

        if (::storage.isInitialized) storage.clearAll()

        initialized = false
        confirmedIdentity = false
        confirmedName = null
        // waiting setUserProperties updates were for the user being cleared
        releaseLifecycleWaits()
        // nothing queued goes out after consent is withdrawn, and nothing waits on it any more
        if (::retryQueue.isInitialized) retryQueue.clear()
        releasePendingIdentifies()
        apiKeyState = null
        userNameState = null
        userIdState = null
        userSignature = null
        userSignatureTime = null
        signatureExpired = false
        onSignatureExpired = null
        secureLock.withLock {
            heldSubmits.clear()
            expiryToldUsers.clear()
        }
        feedbackTransactions = null
        deviceIdState = null
        sessionIdState = null
        localeState = null
        countryState = null
        themeState = Theme.SYSTEM
        currentScreen = null
        pendingResponses = mutableMapOf()
        isSessionPaused = false
        isSessionStopped = false

        EncatchInternalEmitter.emit(InternalEvent.UserIdentified(null, null))
    }

    // ============================================================================
    // Session management
    // ============================================================================

    @Throws(Exception::class)
    suspend fun startSession(options: StartSessionOptions? = null) {
        if (!initialized) return

        isSessionStopped = false
        isSessionPaused = false
        scope.launch { storage.clearSessionStopped() }

        if (isFullScreenState) return

        sessionIdState = storage.getOrCreateSessionId()
        sessionManager.startPingIntervalNow()

        if (options?.skipImmediatePing != true) {
            scope.launch { runCatching { doPing() } }
        }
        if (options?.skipImmediateTrackScreen != true) {
            currentScreen?.let { screen -> scope.launch { runCatching { trackScreen(screen) } } }
        }
    }

    fun pauseSession() {
        if (isSessionPaused) return
        isSessionPaused = true
        sessionManager.stopPingInterval()
    }

    fun resumeSession() {
        if (!isSessionPaused) return
        isSessionPaused = false
        // a stopSession() in effect wins: only startSession() or a successful identify restarts pings
        if (isSessionStopped) return
        sessionManager.startPingInterval()
    }

    @Throws(Exception::class)
    suspend fun stopSession() {
        if (isSessionStopped) return
        isSessionStopped = true
        // the next identify turns the session back on, so it must go out even if it matches
        clearIdentifyMemory()
        isSessionPaused = false
        sessionManager.stopPingIntervalNow()
        cancelPendingAutoShow()
        EncatchInternalEmitter.emit(InternalEvent.DismissForm())
        isFormVisible = false
        checkSurveyIdle()
        storage.setSessionStopped()
    }

    @Throws(Exception::class)
    suspend fun resetUser() {
        if (!initialized) return
        identityMutex.withLock { withContext(NonCancellable) { resetNow() } }
    }

    private suspend fun resetNow() {
        // a queued identify of the user being reset is not sent any more
        retryQueue.removeQueued("identifyUser")
        clearIdentifyMemory()
        collapser.clear()
        // the in-memory identity first, so nothing goes out with a half-cleared one
        val previous = userNameState
        confirmedIdentity = false
        confirmedName = null
        userIdState = null
        userNameState = null
        userSignature = null
        userSignatureTime = null
        val dropped = secureLock.withLock {
            signatureExpired = false
            expiryToldUsers.clear()
            heldSubmits.size.also { heldSubmits.clear() }
        }
        if (dropped > 0) logger.warn("[Encatch] $dropped held survey submit(s) dropped: the user was reset")
        feedbackTransactions = null
        localeState = null
        countryState = null

        previous?.let { name ->
            storage.clearUserId(name)
            storage.clearFeedbackTransactions(name)
            storage.clearUserSignature(name)
        }
        storage.clearFeedbackTransactions("anonymous")
        storage.clearUserName()
        storage.clearSession()
        storage.clearPreferences()

        sessionIdState = storage.getOrCreateSessionId()

        // a saved stopSession() is cleared too
        isSessionPaused = false
        isSessionStopped = false
        storage.clearSessionStopped()
        sessionManager.stopPingIntervalNow()
        cancelPendingAutoShow()

        EncatchInternalEmitter.emit(InternalEvent.UserIdentified(null, null))
    }

    fun setFormVisible(visible: Boolean) = setFormVisible(visible, null)

    /**
     * The UI says a survey opened or closed. [formId]: the survey it is about (the id it was shown
     * by) — a close of a survey since replaced by another is ignored, so the one on screen stays
     * "open" for the one-at-a-time guard.
     */
    fun setFormVisible(visible: Boolean, formId: String?) {
        if (!visible && formId != null && openFormIds.isNotEmpty() && formId !in openFormIds) {
            logger.debug("close of $formId ignored: another survey is open")
            return
        }
        isFormVisible = visible
        if (!visible) {
            openFormIds = emptySet()
            checkSurveyIdle()
        }
    }

    /**
     * Flushes the offline retry queue. The RN SDK does this automatically on `AppState` foreground;
     * on Android the `:android` module calls this from `ProcessLifecycleOwner`'s ON_START.
     */
    fun flushRetryQueue() {
        if (::retryQueue.isInitialized) scope.launch { retryQueue.flush() }
    }

    // ============================================================================
    // Events
    // ============================================================================

    fun on(callback: EventCallback): () -> Unit {
        eventCallbacks.add(callback)
        return { off(callback) }
    }

    fun off(callback: EventCallback) {
        eventCallbacks.remove(callback)
    }

    fun emitEvent(eventType: EventType, payload: EventPayload) {
        val full = payload.copy(timestamp = currentTimeMillis())
        eventCallbacks.toList().forEach { cb -> runCatching { cb(eventType, full) } }
    }

    fun stop() {
        if (::sessionManager.isInitialized) sessionManager.stopPingInterval()
        // anything waiting for a survey to close or an identify gives up
        releaseLifecycleWaits()
    }

    // ============================================================================
    // Ping
    // ============================================================================

    /** The ping loop's ping: none while stopSession() is in effect. */
    private suspend fun backgroundPing() {
        if (isSessionStopped) {
            logger.debug("ping skipped: session stopped")
            return
        }
        doPing()
    }

    private suspend fun doPing() {
        if (rateLimiter.isPaused(RateBucket.TRACKING)) {
            logger.debug("ping skipped: rate limited")
            return
        }
        if (signatureExpired) {
            logger.debug("ping skipped: secure signature expired")
            return
        }
        val deviceInfo = buildDeviceInfo(currentScreen)
        val req = buildJsonObject {
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }
        val res = apiClient.post(Endpoints.PING, req)
        applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())
        handleSurveyInReply(res)
    }

    private suspend fun applyFeedbackTransactions(res: JsonObject) {
        res["\$feedbackTransactions"]?.jsonPrimitive?.contentOrNull?.let {
            feedbackTransactions = it
            storage.setFeedbackTransactions(userNameState ?: "anonymous", it)
        }
    }

    // ============================================================================
    // Device info builder
    // ============================================================================

    private fun buildDeviceInfo(screenName: String? = null): ApiDeviceInfo {
        val facts = collectPlatformDeviceFacts()
        return ApiDeviceInfo(
            deviceOs = SDK_PLATFORM,
            deviceVersion = facts.osVersion,
            deviceOsVersion = facts.osVersion,
            deviceType = "native",
            deviceSize = null,
            sdkVersion = SDK_VERSION,
            appVersion = appVersionState,
            app = appPackageName,
            deviceLanguage = facts.deviceLocale,
            userLanguage = localeState ?: facts.deviceLocale,
            countryCode = countryState,
            preferredTheme = themeState.wireValue,
            timezone = facts.timezone,
            urlOrScreenName = screenName ?: currentScreen,
        )
    }

    // ============================================================================
    // Pending completion CTA scheduling — wired by the :android module
    // ============================================================================

    /** Set by the `:android` module's UI layer so exit_form CTAs can open Custom Tabs / browser intents. */
    var pendingCtaScheduler: PendingCompletionCtaScheduler? = null

    // ============================================================================
    // Getters
    // ============================================================================

    val isInitialized: Boolean get() = initialized
    val apiKey: String? get() = apiKeyState
    val baseUrl: String get() = apiBaseUrlState
    val webHost: String get() = webHostState
    val isFullScreen: Boolean get() = isFullScreenState
    val theme: Theme get() = themeState
    val locale: String? get() = localeState
    val deviceId: String? get() = deviceIdState
    val sessionId: String? get() = sessionIdState
    val userName: String? get() = userNameState

    /**
     * Whether the SDK has a confirmed identity (RN 1.5.0 item 2): `true` once an identify succeeded,
     * or at launch when a user saved by an earlier run is restored with their user id. Synchronous.
     *
     * - `false` before [init], for a visitor, and while a first identify is pending or after it failed.
     * - Switching from one user to another keeps describing the previous user until the new user's
     *   identify succeeds (requests already go out as a visitor during the switch).
     * - `false` after [resetUser] (also the server's "retries exhausted" reset) and [clearAll];
     *   [stopSession] and an expired secure signature leave it unchanged.
     * - With `EncatchConfig.persistUserAcrossRestarts = false` it is `false` at every launch until the
     *   app identifies.
     */
    fun isIdentified(): Boolean = initialized && confirmedIdentity

    /** Tests only: what a new app run starts without (the in-memory user store), for a "restart". */
    internal fun forgetAppRunForTest() {
        memoryUserStore.clear()
    }
    val userId: String? get() = userIdState
    val debugMode: Boolean get() = debugModeState
}

private fun ApiDeviceInfo.toJson(): JsonObject = buildJsonObject {
    deviceOs?.let { put("\$deviceOs", it) }
    deviceVersion?.let { put("\$deviceVersion", it) }
    deviceOsVersion?.let { put("\$deviceOsVersion", it) }
    deviceType?.let { put("\$deviceType", it) }
    deviceSize?.let { put("\$deviceSize", it) }
    sdkVersion?.let { put("\$sdkVersion", it) }
    appVersion?.let { put("\$appVersion", it) }
    app?.let { put("\$app", it) }
    deviceLanguage?.let { put("\$deviceLanguage", it) }
    userLanguage?.let { put("\$userLanguage", it) }
    countryCode?.let { put("\$countryCode", it) }
    preferredTheme?.let { put("\$preferredTheme", it) }
    timezone?.let { put("\$timezone", it) }
    urlOrScreenName?.let { put("\$urlOrScreenName", it) }
}

private fun UserTraits.toJson(): JsonObject = buildJsonObject {
    set?.let { put("\$set", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    setOnce?.let { put("\$setOnce", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    increment?.let { put("\$increment", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    decrement?.let { put("\$decrement", buildJsonObject { it.forEach { (k, v) -> put(k, v) } }) }
    unset?.let { list -> put("\$unset", JsonArray(list.map { JsonPrimitive(it) })) }
}

private fun ContextValue.toJson(): JsonElement = when (this) {
    is ContextValue.StringValue -> JsonPrimitive(value)
    is ContextValue.NumberValue -> JsonPrimitive(value)
    is ContextValue.BooleanValue -> JsonPrimitive(value)
    is ContextValue.DateValue -> JsonPrimitive(isoStringFromEpochMillis(epochMillis))
}

// internal (not private) so commonTest can exercise the top-level parse contract directly —
// notably that projectI18nFileUrl is read from the response root, never from nested config.
internal fun JsonObject.toShowFormResponse(): ShowFormResponse = ShowFormResponse(
    feedbackConfigurationId = this["feedbackConfigurationId"]?.jsonPrimitive?.contentOrNull ?: "",
    feedbackIdentifier = this["feedbackIdentifier"]?.jsonPrimitive?.contentOrNull,
    triggerType = this["triggerType"]?.jsonPrimitive?.contentOrNull?.let { if (it == "automatic") TriggerType.AUTOMATIC else TriggerType.MANUAL },
    formConfiguration = this["formConfiguration"]?.jsonObject,
    questionnaireFields = this["questionnaireFields"],
    otherConfigurationProperties = this["otherConfigurationProperties"],
    appearanceProperties = this["appearanceProperties"],
    partialResponseEnabled = this["partialResponseEnabled"]?.jsonPrimitive?.booleanOrNull,
    contact = this["contact"]?.jsonObject,
    projectI18nFileUrl = this["projectI18nFileUrl"]?.jsonPrimitive?.contentOrNull,
    pingAgainIn = this["pingAgainIn"]?.jsonPrimitive?.doubleOrNull,
    pingOnNextPageVisit = this["pingOnNextPageVisit"]?.jsonPrimitive?.booleanOrNull,
    feedbackTransactions = this["\$feedbackTransactions"]?.jsonPrimitive?.contentOrNull,
)

/** Survey submits kept while the secure signature is expired (oldest dropped first). */
private const val MAX_HELD_SUBMITS = 10

/** submit-form attempts in all when the API answers 429. */
private const val SUBMIT_MAX_ATTEMPTS = 3

/** Identical trackEvent / trackScreen calls within this window are sent once. */
internal const val COLLAPSE_WINDOW_MS = 1_000L

/**
 * Collapses identical calls (RN 1.4.4 item 4): the same event within [windowMs] of the last one
 * sent, and the same screen as the last one tracked within [windowMs].
 */
internal class CallCollapser(
    private val windowMs: Long = COLLAPSE_WINDOW_MS,
    private val clock: () -> Long = { currentTimeMillis() },
) {
    private val lock = Mutex()
    private val lastEventAt = mutableMapOf<String, Long>()
    private var lastScreen: Pair<String, Long>? = null

    suspend fun admitEvent(name: String): Boolean = lock.withLock {
        val now = clock()
        val last = lastEventAt[name]
        if (last != null && now - last < windowMs) return@withLock false
        lastEventAt[name] = now
        if (lastEventAt.size > 200) lastEventAt.entries.removeAll { now - it.value >= windowMs }
        true
    }

    suspend fun admitScreen(name: String): Boolean = lock.withLock {
        val now = clock()
        val last = lastScreen
        if (last != null && last.first == name && now - last.second < windowMs) return@withLock false
        lastScreen = name to now
        true
    }

    suspend fun clear() = lock.withLock {
        lastEventAt.clear()
        lastScreen = null
    }
}

/**
 * Traits as compared for duplicates (RN SDK 1.5.0 `traitsForDedupe`): empty operations dropped
 * (`$set: {}`, `$unset: []`), `$unset` as a sorted copy (a set of names); null when nothing is left.
 * The caller's traits are never changed.
 */
internal fun traitsForDedupe(traits: UserTraits?): JsonObject? {
    if (traits == null) return null
    val out = buildJsonObject {
        traits.set?.takeIf { it.isNotEmpty() }?.let { m -> put("\$set", JsonObject(m)) }
        traits.setOnce?.takeIf { it.isNotEmpty() }?.let { m -> put("\$setOnce", JsonObject(m)) }
        traits.increment?.takeIf { it.isNotEmpty() }?.let { m -> put("\$increment", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
        traits.decrement?.takeIf { it.isNotEmpty() }?.let { m -> put("\$decrement", JsonObject(m.mapValues { JsonPrimitive(it.value) })) }
        traits.unset?.takeIf { it.isNotEmpty() }?.let { l -> put("\$unset", JsonArray(l.sorted().map { JsonPrimitive(it) })) }
    }
    return out.takeIf { it.isNotEmpty() }
}

/** Traits a request body can't carry: a number that isn't finite (NaN, infinity) anywhere in them. */
internal fun hasUnsendableValue(traits: UserTraits?): Boolean {
    if (traits == null) return false
    fun bad(e: JsonElement): Boolean = when (e) {
        is JsonObject -> e.values.any(::bad)
        is JsonArray -> e.any(::bad)
        is JsonPrimitive -> !e.isString && e.doubleOrNull?.isFinite() == false
        else -> false
    }
    return listOfNotNull(traits.set?.values, traits.setOnce?.values).flatten().any(::bad) ||
        listOfNotNull(traits.increment?.values, traits.decrement?.values).flatten().any { !it.isFinite() }
}

/** JSON with object keys sorted, so equal values give equal strings (the identify dedupe key). */
internal fun stableJsonKey(value: JsonElement): String {
    fun norm(v: JsonElement): JsonElement = when (v) {
        is JsonObject -> JsonObject(v.entries.sortedBy { it.key }.associate { it.key to norm(it.value) })
        is JsonArray -> JsonArray(v.map { norm(it) })
        else -> v
    }
    return norm(value).toString()
}

internal expect fun createDefaultEngine(): io.ktor.client.engine.HttpClientEngine
internal expect fun isoStringFromEpochMillis(epochMillis: Long): String
internal expect suspend fun readContentUri(uri: String): ByteArray
