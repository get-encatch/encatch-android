package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Config
    private var apiKeyState: String? = null
    private var apiBaseUrlState: String = DEFAULT_API_BASE_URL
    private var webHostState: String = DEFAULT_WEB_HOST
    private var isFullScreenState = false
    private var onBeforeShowForm: BeforeShowFormInterceptor? = null

    // Identity
    private var userNameState: String? = null
    private var userIdState: String? = null
    private var userSignature: String? = null

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

    // Form visibility (suppresses ping)
    private var isFormVisible = false

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
    private lateinit var apiClient: EncatchApiClient
    private lateinit var retryQueue: RetryQueue
    private lateinit var sessionManager: SessionManager

    // ============================================================================
    // Initialisation
    // ============================================================================

    suspend fun init(apiKey: String, config: EncatchConfig? = null) {
        debugModeState = config?.debugMode ?: false

        if (initialized) return

        apiKeyState = apiKey
        apiBaseUrlState = (config?.apiBaseUrl ?: DEFAULT_API_BASE_URL).trimEnd('/')
        webHostState = (config?.webHost ?: DEFAULT_WEB_HOST).trimEnd('/')
        isFullScreenState = config?.isFullScreen ?: false
        config?.theme?.let { themeState = it }
        onBeforeShowForm = config?.onBeforeShowForm

        storage = EncatchStorage()
        apiClient = EncatchApiClient(
            httpClient = createHttpClient(createDefaultEngine()),
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
                )
            },
            onUserPendingRetryExhausted = {
                sessionManager.stopPingInterval()
                resetUser()
            },
        )
        retryQueue = RetryQueue(scope, storage)
        sessionManager = SessionManager(scope, isFormVisible = { isFormVisible }, onPing = { doPing() })

        val storedName = storage.getUserName()
        deviceIdState = storage.getOrCreateDeviceId()
        sessionIdState = storage.getOrCreateSessionId()
        val prefs = storage.getPreferences()
        val facts = collectPlatformDeviceFacts()
        appPackageName = facts.appPackageName
        appVersionState = config?.appVersion ?: facts.appVersion ?: "1.0.0"

        prefs.locale?.let { localeState = it }
        prefs.country?.let { countryState = it }

        if (storedName != null) {
            userNameState = storedName
            userIdState = storage.getUserId(storedName)
            feedbackTransactions = storage.getFeedbackTransactions(storedName)
        } else {
            feedbackTransactions = storage.getFeedbackTransactions("anonymous")
        }

        initialized = true

        scope.launch { retryQueue.flush() }
    }

    // ============================================================================
    // Identity
    // ============================================================================

    suspend fun identifyUser(userName: String, traits: UserTraits? = null, options: IdentifyOptions? = null) {
        if (!initialized) return

        userNameState = userName
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
            (options?.secure?.signature ?: userSignature)?.let { put("userSignature", it) }
            put("\$deviceInfo", deviceInfo.toJson())
            traits?.let { put("userAttributes", it.toJson()) }
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }

        retryQueue.enqueue("identifyUser") {
            val res = apiClient.post(Endpoints.IDENTIFY_USER, req, signatureTime = options?.secure?.generatedDateTimeInUtc)
            res["userId"]?.jsonPrimitive?.contentOrNull?.let {
                userIdState = it
                storage.setUserId(userName, it)
            }
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

            res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.let {
                showFormById(it, TriggerType.AUTOMATIC)
            }
        }

        scope.launch { retryQueue.flush() }
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    fun setLocale(locale: String) {
        localeState = locale
        scope.launch { storage.setPreferences(locale = locale) }
    }

    fun setCountry(country: String) {
        countryState = country
        scope.launch { storage.setPreferences(country = country) }
    }

    fun setTheme(theme: Theme) {
        themeState = theme
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    suspend fun trackEvent(eventName: String) {
        if (!initialized || isFullScreenState) return

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
            res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.let { showFormById(it, TriggerType.AUTOMATIC) }
        }

        scope.launch { retryQueue.flush() }
    }

    /**
     * Best-effort call for form lifecycle events; not enqueued/retried, matches `_trackFormEvent`.
     * Public so the `:android` UI module's WebView bridge can call it for form:started/answered/show,
     * mirroring how the RN SDK's `useEncatchFormWebView` hook calls `Encatch._trackFormEvent`.
     */
    suspend fun trackFormEvent(eventName: String, feedbackConfigurationId: String?) {
        if (!initialized) return
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
            res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.let { showFormById(it, TriggerType.AUTOMATIC) }
        }
    }

    suspend fun trackScreen(screenName: String) {
        if (!initialized || isFullScreenState) return

        currentScreen = screenName
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
            res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.let { showFormById(it, TriggerType.AUTOMATIC) }
            res["nextFeedbackId"]?.jsonPrimitive?.contentOrNull?.let { nextId ->
                val delayS = res["onPageDelay"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                scope.launch {
                    delay((delayS * 1000).toLong())
                    showFormById(nextId, TriggerType.AUTOMATIC, ResetMode.ALWAYS)
                }
            }
        }

        scope.launch { retryQueue.flush() }
    }

    // ============================================================================
    // Form display
    // ============================================================================

    suspend fun showForm(formId: String, options: ShowFormOptions? = null) {
        if (!initialized) return
        showFormInternal(formId, options?.reset ?: ResetMode.ALWAYS, TriggerType.MANUAL, options?.context)
    }

    private suspend fun showFormById(
        formConfigurationId: String,
        triggerType: TriggerType,
        reset: ResetMode = ResetMode.ALWAYS,
    ) {
        runCatching { showFormInternal(formConfigurationId, reset, triggerType, null) }
    }

    private suspend fun showFormInternal(
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
        }
    }

    suspend fun dismissForm(formConfigurationId: String? = null) {
        if (!initialized) return

        pendingCtaScheduler?.cancel(formConfigurationId)
        EncatchInternalEmitter.emit(InternalEvent.DismissForm(formConfigurationId))
        isFormVisible = false

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

    suspend fun submitForm(params: SubmitFormRequest) {
        if (!initialized) return
        val deviceInfo = buildDeviceInfo()
        val req = buildJsonObject {
            params.triggerType?.let { put("triggerType", it) }
            put("formDetails", EncatchJson.encodeToJsonElement(FormDetails.serializer(), params.formDetails))
            put("\$deviceInfo", deviceInfo.toJson())
        }
        runCatching {
            val res = apiClient.post(Endpoints.SUBMIT_FORM, req)
            applyFeedbackTransactions(res)
            sessionManager.handleResponseMeta(res.toResponseMeta())
        }
    }

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

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

    suspend fun streamQnaWithAi(params: QnaWithAiRequest, onChunk: (String) -> Unit, onDone: (String) -> Unit) {
        if (!initialized) throw EncatchNotInitializedException()
        require(params.feedbackConfigurationId.isNotEmpty() && params.questionId.isNotEmpty() && params.conversation.isNotEmpty()) {
            "[Encatch] feedbackConfigurationId, questionId, and conversation are required for qna-with-ai stream"
        }
        apiClient.streamQnaWithAi(params.feedbackConfigurationId, params.questionId, params.conversation, onChunk, onDone)
    }

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

    suspend fun clearAll() {
        if (::sessionManager.isInitialized) sessionManager.stopPingInterval()

        EncatchInternalEmitter.emit(InternalEvent.DismissForm())
        isFormVisible = false

        if (::storage.isInitialized) storage.clearAll()

        initialized = false
        apiKeyState = null
        userNameState = null
        userIdState = null
        userSignature = null
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

    suspend fun startSession(options: StartSessionOptions? = null) {
        if (!initialized) return

        isSessionStopped = false
        isSessionPaused = false
        scope.launch { storage.clearSessionStopped() }

        if (isFullScreenState) return

        sessionIdState = storage.getOrCreateSessionId()
        sessionManager.startPingInterval()

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
        sessionManager.startPingInterval()
    }

    suspend fun stopSession() {
        if (isSessionStopped) return
        isSessionStopped = true
        isSessionPaused = false
        sessionManager.stopPingInterval()
        EncatchInternalEmitter.emit(InternalEvent.DismissForm())
        isFormVisible = false
        storage.setSessionStopped()
    }

    suspend fun resetUser() {
        userNameState?.let { name ->
            storage.clearUserId(name)
            storage.clearFeedbackTransactions(name)
        }
        storage.clearFeedbackTransactions("anonymous")
        storage.clearUserName()
        storage.clearSession()
        storage.clearPreferences()

        userNameState = null
        userIdState = null
        userSignature = null
        feedbackTransactions = null
        localeState = null
        countryState = null
        sessionIdState = storage.getOrCreateSessionId()

        isSessionPaused = false
        isSessionStopped = false
        sessionManager.stopPingInterval()

        EncatchInternalEmitter.emit(InternalEvent.UserIdentified(null, null))
    }

    fun setFormVisible(visible: Boolean) {
        isFormVisible = visible
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
    }

    // ============================================================================
    // Ping
    // ============================================================================

    private suspend fun doPing() {
        val deviceInfo = buildDeviceInfo(currentScreen)
        val req = buildJsonObject {
            put("\$deviceInfo", deviceInfo.toJson())
            feedbackTransactions?.let { put("\$feedbackTransactions", it) }
        }
        val res = apiClient.post(Endpoints.PING, req)
        applyFeedbackTransactions(res)
        sessionManager.handleResponseMeta(res.toResponseMeta())
        res["formConfigurationId"]?.jsonPrimitive?.contentOrNull?.let { showFormById(it, TriggerType.AUTOMATIC) }
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

private fun JsonObject.toShowFormResponse(): ShowFormResponse = ShowFormResponse(
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

internal expect fun createDefaultEngine(): io.ktor.client.engine.HttpClientEngine
internal expect fun isoStringFromEpochMillis(epochMillis: Long): String
internal expect suspend fun readContentUri(uri: String): ByteArray
