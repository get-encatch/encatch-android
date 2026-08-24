package com.encatch.sdk

import com.encatch.core.EncatchJson

/**
 * Android backing for [Encatch] — near-zero-cost passthrough to `:core`'s Kotlin `Encatch`
 * singleton (Android's native language *is* Kotlin, so `:core` is a real native SDK here, not a
 * shared layer — see this file's `iosMain` sibling for the cinterop-bridged iOS side).
 *
 * Every member below converts [Encatch]'s public mirror types (`Types.kt`) to/from `:core`'s real
 * types at the boundary, then calls straight through. See `Types.kt`'s doc comment for why these
 * mirror types exist instead of `:kmp-sdk` depending on `:core`'s types directly in `commonMain`.
 */
actual object Encatch {

    // ============================================================================
    // Initialisation
    // ============================================================================

    actual suspend fun init(apiKey: String, config: EncatchConfig?) {
        com.encatch.core.Encatch.init(apiKey, config?.toCore())
    }

    actual val isInitialized: Boolean get() = com.encatch.core.Encatch.isInitialized

    // ============================================================================
    // Identity
    // ============================================================================

    actual suspend fun identifyUser(userName: String, traits: UserTraits?, options: IdentifyOptions?) {
        com.encatch.core.Encatch.identifyUser(userName, traits?.toCore(), options?.toCore())
    }

    // ============================================================================
    // Preferences
    // ============================================================================

    actual fun setLocale(locale: String) {
        com.encatch.core.Encatch.setLocale(locale)
    }

    actual fun setCountry(country: String) {
        com.encatch.core.Encatch.setCountry(country)
    }

    actual fun setTheme(theme: Theme) {
        com.encatch.core.Encatch.setTheme(theme.toCore())
    }

    // ============================================================================
    // Event tracking
    // ============================================================================

    actual suspend fun trackEvent(eventName: String) {
        com.encatch.core.Encatch.trackEvent(eventName)
    }

    actual suspend fun trackFormEvent(eventName: String, feedbackConfigurationId: String?) {
        com.encatch.core.Encatch.trackFormEvent(eventName, feedbackConfigurationId)
    }

    actual suspend fun trackScreen(screenName: String) {
        com.encatch.core.Encatch.trackScreen(screenName)
    }

    // ============================================================================
    // Form display
    // ============================================================================

    actual suspend fun showForm(formId: String, options: ShowFormOptions?) {
        com.encatch.core.Encatch.showForm(formId, options?.toCore())
    }

    actual suspend fun dismissForm(formConfigurationId: String?) {
        com.encatch.core.Encatch.dismissForm(formConfigurationId)
    }

    // ============================================================================
    // Form response helpers
    // ============================================================================

    actual fun addToResponse(questionId: String, value: Any?) {
        com.encatch.core.Encatch.addToResponse(questionId, value)
    }

    actual fun getPendingResponses(): Map<String, Any?> = com.encatch.core.Encatch.getPendingResponses()

    actual fun clearPendingResponses() {
        com.encatch.core.Encatch.clearPendingResponses()
    }

    // ============================================================================
    // Submit form
    // ============================================================================

    actual suspend fun submitForm(requestJson: String) {
        val request = EncatchJson.decodeFromString(
            com.encatch.core.SubmitFormRequest.serializer(),
            requestJson,
        )
        com.encatch.core.Encatch.submitForm(request)
    }

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

    actual suspend fun refineText(params: RefineTextRequest): RefineTextResponse =
        com.encatch.core.Encatch.refineText(params.toCore()).toSdk()

    actual suspend fun streamQnaWithAi(params: QnaWithAiRequest, onChunk: (String) -> Unit, onDone: (String) -> Unit) {
        com.encatch.core.Encatch.streamQnaWithAi(params.toCore(), onChunk, onDone)
    }

    actual suspend fun uploadFile(params: UploadFileRequest): UploadFileResponse =
        com.encatch.core.Encatch.uploadFile(params.toCore()).toSdk()

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    actual suspend fun clearAll() {
        com.encatch.core.Encatch.clearAll()
    }

    // ============================================================================
    // Session management
    // ============================================================================

    actual suspend fun startSession(options: StartSessionOptions?) {
        com.encatch.core.Encatch.startSession(options?.toCore())
    }

    actual fun pauseSession() {
        com.encatch.core.Encatch.pauseSession()
    }

    actual fun resumeSession() {
        com.encatch.core.Encatch.resumeSession()
    }

    actual suspend fun stopSession() {
        com.encatch.core.Encatch.stopSession()
    }

    actual suspend fun resetUser() {
        com.encatch.core.Encatch.resetUser()
    }

    actual fun setFormVisible(visible: Boolean) {
        com.encatch.core.Encatch.setFormVisible(visible)
    }

    actual fun flushRetryQueue() {
        com.encatch.core.Encatch.flushRetryQueue()
    }

    // ============================================================================
    // Events
    // ============================================================================

    actual fun on(callback: EventCallback): () -> Unit {
        val coreCallback: com.encatch.core.EventCallback = { type, payload ->
            callback(type.toSdk(), payload.toSdk())
        }
        return com.encatch.core.Encatch.on(coreCallback)
    }

    actual fun emitEvent(eventType: EventType, payload: EventPayload) {
        com.encatch.core.Encatch.emitEvent(eventType.toCore(), payload.toCore())
    }

    actual fun setOnNetworkLog(callback: ((NetworkLogEntry) -> Unit)?) {
        com.encatch.core.Encatch.onNetworkLog = callback?.let { cb ->
            { entry ->
                cb(
                    NetworkLogEntry(
                        timestampMs = entry.timestampMs,
                        method = entry.method,
                        endpoint = entry.endpoint,
                        url = entry.url,
                        requestHeaders = entry.requestHeaders,
                        requestBody = entry.requestBody,
                        status = entry.status,
                        responseBody = entry.responseBody,
                        durationMs = entry.durationMs,
                        error = entry.error,
                        responseHeaders = entry.responseHeaders,
                    ),
                )
            }
        }
    }

    actual fun stop() {
        com.encatch.core.Encatch.stop()
    }

    // ============================================================================
    // Getters
    // ============================================================================

    actual val apiKey: String? get() = com.encatch.core.Encatch.apiKey
    actual val baseUrl: String get() = com.encatch.core.Encatch.baseUrl
    actual val webHost: String get() = com.encatch.core.Encatch.webHost
    actual val isFullScreen: Boolean get() = com.encatch.core.Encatch.isFullScreen
    actual val theme: Theme get() = com.encatch.core.Encatch.theme.toSdk()
    actual val locale: String? get() = com.encatch.core.Encatch.locale
    actual val deviceId: String? get() = com.encatch.core.Encatch.deviceId
    actual val sessionId: String? get() = com.encatch.core.Encatch.sessionId
    actual val userName: String? get() = com.encatch.core.Encatch.userName
    actual val userId: String? get() = com.encatch.core.Encatch.userId
    actual val debugMode: Boolean get() = com.encatch.core.Encatch.debugMode
}

// ============================================================================
// Type conversions: com.encatch.sdk.* (Types.kt) <-> com.encatch.core.*
// ============================================================================

private fun Theme.toCore(): com.encatch.core.Theme = when (this) {
    Theme.LIGHT -> com.encatch.core.Theme.LIGHT
    Theme.DARK -> com.encatch.core.Theme.DARK
    Theme.SYSTEM -> com.encatch.core.Theme.SYSTEM
}

private fun com.encatch.core.Theme.toSdk(): Theme = when (this) {
    com.encatch.core.Theme.LIGHT -> Theme.LIGHT
    com.encatch.core.Theme.DARK -> Theme.DARK
    com.encatch.core.Theme.SYSTEM -> Theme.SYSTEM
}

private fun ResetMode.toCore(): com.encatch.core.ResetMode = when (this) {
    ResetMode.ALWAYS -> com.encatch.core.ResetMode.ALWAYS
    ResetMode.ON_COMPLETE -> com.encatch.core.ResetMode.ON_COMPLETE
    ResetMode.NEVER -> com.encatch.core.ResetMode.NEVER
}

private fun com.encatch.core.ResetMode.toSdk(): ResetMode = ResetMode.valueOf(name)

private fun com.encatch.core.TriggerType.toSdk(): TriggerType = TriggerType.valueOf(name)

private fun com.encatch.core.ShowFormInterceptorPayload.toSdk(): ShowFormInterceptorPayload = ShowFormInterceptorPayload(
    formId = formId,
    resetMode = resetMode.toSdk(),
    triggerType = triggerType.toSdk(),
    prefillResponses = prefillResponses,
    locale = locale,
    theme = theme?.toSdk(),
    context = context,
    formConfigJson = formConfig.questionnaireFields?.toString(),
)

private fun ContextValue.toCore(): com.encatch.core.ContextValue = when (this) {
    is ContextValue.StringValue -> com.encatch.core.ContextValue.StringValue(value)
    is ContextValue.NumberValue -> com.encatch.core.ContextValue.NumberValue(value)
    is ContextValue.BooleanValue -> com.encatch.core.ContextValue.BooleanValue(value)
    is ContextValue.DateValue -> com.encatch.core.ContextValue.DateValue(epochMillis)
}

private fun EncatchConfig.toCore(): com.encatch.core.EncatchConfig {
    val defaults = com.encatch.core.EncatchConfig()
    return com.encatch.core.EncatchConfig(
        apiBaseUrl = apiBaseUrl ?: defaults.apiBaseUrl,
        webHost = webHost ?: defaults.webHost,
        theme = theme.toCore(),
        isFullScreen = isFullScreen,
        debugMode = debugMode,
        appVersion = appVersion,
        onBeforeShowForm = onBeforeShowForm?.let { interceptor ->
            { payload: com.encatch.core.ShowFormInterceptorPayload -> interceptor(payload.toSdk()) }
        },
    )
}

private fun UserTraits.toCore(): com.encatch.core.UserTraits = com.encatch.core.UserTraits(
    set = set,
    setOnce = setOnce,
    increment = increment,
    decrement = decrement,
    unset = unset,
)

private fun SecureOptions.toCore(): com.encatch.core.SecureOptions =
    com.encatch.core.SecureOptions(signature = signature, generatedDateTimeInUtc = generatedDateTimeInUtc)

private fun IdentifyOptions.toCore(): com.encatch.core.IdentifyOptions = com.encatch.core.IdentifyOptions(
    locale = locale,
    country = country,
    secure = secure?.toCore(),
)

private fun ShowFormOptions.toCore(): com.encatch.core.ShowFormOptions = com.encatch.core.ShowFormOptions(
    reset = reset.toCore(),
    context = context.mapValues { it.value.toCore() },
)

private fun StartSessionOptions.toCore(): com.encatch.core.StartSessionOptions = com.encatch.core.StartSessionOptions(
    skipImmediatePing = skipImmediatePing,
    skipImmediateTrackScreen = skipImmediateTrackScreen,
)

private fun RefineTextRequest.toCore(): com.encatch.core.RefineTextRequest = com.encatch.core.RefineTextRequest(
    questionId = questionId,
    feedbackConfigurationId = feedbackConfigurationId,
    userText = userText,
)

private fun com.encatch.core.RefineTextResponse.toSdk(): RefineTextResponse = RefineTextResponse(
    message = message,
    refinedText = refinedText,
    status = status,
    error = error,
)

private fun QnaWithAiConversationTurn.toCore(): com.encatch.core.QnaWithAiConversationTurn =
    com.encatch.core.QnaWithAiConversationTurn(question = question, answer = answer)

private fun QnaWithAiRequest.toCore(): com.encatch.core.QnaWithAiRequest = com.encatch.core.QnaWithAiRequest(
    feedbackConfigurationId = feedbackConfigurationId,
    questionId = questionId,
    conversation = conversation.map { it.toCore() },
)

private fun UploadFileRequest.toCore(): com.encatch.core.UploadFileRequest = com.encatch.core.UploadFileRequest(
    feedbackConfigurationId = feedbackConfigurationId,
    questionId = questionId,
    file = com.encatch.core.UploadFileSource.Bytes(fileBytes, mimeType),
    fileName = fileName,
    onProgress = onProgress,
)

private fun com.encatch.core.UploadFileResponse.toSdk(): UploadFileResponse = UploadFileResponse(fileUrl = fileUrl)

private fun EventType.toCore(): com.encatch.core.EventType = com.encatch.core.EventType.valueOf(name)

private fun com.encatch.core.EventType.toSdk(): EventType = EventType.valueOf(name)

private fun EventPayload.toCore(): com.encatch.core.EventPayload = com.encatch.core.EventPayload(
    formId = formId,
    timestamp = timestamp,
    data = data,
)

private fun com.encatch.core.EventPayload.toSdk(): EventPayload = EventPayload(
    formId = formId,
    timestamp = timestamp,
    data = data,
)
