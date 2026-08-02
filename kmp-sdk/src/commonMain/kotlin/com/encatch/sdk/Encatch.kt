package com.encatch.sdk

/**
 * The real, publishable Encatch KMP SDK entry point. Mirrors every public member of `:core`'s
 * `com.encatch.core.Encatch` singleton (see `core/src/commonMain/kotlin/com/encatch/core/Encatch.kt`
 * for canonical behavior/semantics of each member — this object only concerns itself with platform
 * routing at the boundary, not reimplementing any SDK logic).
 *
 * This is a thin platform-routing layer, not a third implementation of the SDK:
 *  - **Android** (`Encatch.android.kt`) forwards 1:1 to `com.encatch.core.Encatch`, unchanged —
 *    Android's native language *is* Kotlin, so `:core` is already a real native SDK there.
 *  - **iOS** (`Encatch.ios.kt`) forwards through Kotlin/Native cinterop to the pure-Swift
 *    `ios-native/` SDK's `@objc` facade (`ios-native/Sources/Encatch/ObjCBridge/EncatchBridge.swift`).
 *
 * A Compose Multiplatform or plain KMP customer adds `:kmp-sdk` as a Gradle dependency and calls
 * `Encatch.init(...)`/`Encatch.showForm(...)`/etc. from `commonMain` — no per-platform bridging code
 * of their own, the same "few lines, no plumbing" experience a native Android or native iOS
 * customer already gets.
 *
 * Background / rationale, including why the public parameter/response types here
 * ([EncatchConfig], [UserTraits], [ShowFormOptions], etc. in `Types.kt`) are small local mirrors of
 * `:core`'s types rather than `:core`'s types directly: see this file's sibling `Types.kt` doc
 * comment, and /Users/godwin/.claude/plans/stateless-floating-ripple.md.
 */
expect object Encatch {

    // ============================================================================
    // Initialisation
    // ============================================================================

    suspend fun init(apiKey: String, config: EncatchConfig? = null)

    val isInitialized: Boolean

    // ============================================================================
    // Identity
    // ============================================================================

    suspend fun identifyUser(userName: String, traits: UserTraits? = null, options: IdentifyOptions? = null)

    // ============================================================================
    // Preferences
    // ============================================================================

    fun setLocale(locale: String)

    fun setCountry(country: String)

    fun setTheme(theme: Theme)

    // ============================================================================
    // Event tracking
    // ============================================================================

    suspend fun trackEvent(eventName: String)

    /** Best-effort call for form lifecycle events; mirrors `core.Encatch.trackFormEvent` (never throws). */
    suspend fun trackFormEvent(eventName: String, feedbackConfigurationId: String? = null)

    suspend fun trackScreen(screenName: String)

    // ============================================================================
    // Form display
    // ============================================================================

    suspend fun showForm(formId: String, options: ShowFormOptions? = null)

    suspend fun dismissForm(formConfigurationId: String? = null)

    // ============================================================================
    // Form response helpers
    // ============================================================================

    fun addToResponse(questionId: String, value: Any?)

    fun getPendingResponses(): Map<String, Any?>

    fun clearPendingResponses()

    // ============================================================================
    // Submit form
    // ============================================================================

    /**
     * Mirrors `core.Encatch.submitForm(_:)`. `requestJson` must be the JSON encoding of `:core`'s
     * `SubmitFormRequest` (`kotlinx.serialization`'s `SubmitFormRequest.serializer()` on Android;
     * the same wire shape `Encatch.swift`'s `SubmitFormRequest: Codable` produces/consumes on iOS).
     *
     * Deviation from a "reuse `:core`'s type directly" 1:1 mirror: `SubmitFormRequest` is a deeply
     * nested tree (`FormDetails` -> `FormResponse` -> `[QuestionResponse]` -> `Answer` -> several
     * more nested sealed types, see `core/.../Answer.kt`). `:kmp-sdk`'s `commonMain` cannot depend
     * on `:core` at all (see `Types.kt`'s doc comment for why), so mirroring that whole tree as
     * plain `commonMain` data classes would be a lot of boilerplate duplicating `:core`'s types by
     * hand and keeping it in lockstep forever. `EncatchBridge.swift` already made exactly this same
     * call at the Swift/ObjC boundary for the same type (JSON string in, `JSONDecoder`'d back into a
     * real `SubmitFormRequest` inside the bridge) — this mirrors that same tradeoff one boundary
     * further out: Android callers build a `com.encatch.core.SubmitFormRequest` and
     * `EncatchJson.encodeToString(SubmitFormRequest.serializer(), it)`; iOS callers do the
     * equivalent from their own `SubmitFormRequest`-shaped JSON. The Android actual round-trips the
     * JSON straight back into `:core`'s real type before calling `com.encatch.core.Encatch.submitForm`.
     */
    suspend fun submitForm(requestJson: String)

    // ============================================================================
    // Refine text / Q&A with AI / upload
    // ============================================================================

    suspend fun refineText(params: RefineTextRequest): RefineTextResponse

    suspend fun streamQnaWithAi(params: QnaWithAiRequest, onChunk: (String) -> Unit, onDone: (String) -> Unit)

    suspend fun uploadFile(params: UploadFileRequest): UploadFileResponse

    // ============================================================================
    // clearAll — full consent withdrawal
    // ============================================================================

    suspend fun clearAll()

    // ============================================================================
    // Session management
    // ============================================================================

    suspend fun startSession(options: StartSessionOptions? = null)

    fun pauseSession()

    fun resumeSession()

    suspend fun stopSession()

    suspend fun resetUser()

    fun setFormVisible(visible: Boolean)

    fun flushRetryQueue()

    // ============================================================================
    // Events
    // ============================================================================

    /**
     * Registers [callback] and returns an unsubscribe function. Deviation from `core.Encatch`'s
     * `on`/`off` pair: `EncatchBridge.swift`'s `onEvent` (which the iOS actual forwards to) already
     * dropped the separate `off(callback)` entry point, since Swift closures aren't `Equatable` and
     * reference-equality of a Kotlin function value isn't a portable concept across the cinterop
     * boundary either — see that file's doc comment. `Encatch.off(callback)` is therefore not part
     * of this expect API; call the returned unsubscribe function instead.
     */
    fun on(callback: EventCallback): () -> Unit

    fun emitEvent(eventType: EventType, payload: EventPayload)

    fun stop()

    // ============================================================================
    // Getters
    // ============================================================================

    val apiKey: String?
    val baseUrl: String
    val webHost: String
    val isFullScreen: Boolean
    val theme: Theme
    val locale: String?
    val deviceId: String?
    val sessionId: String?
    val userName: String?
    val userId: String?
    val debugMode: Boolean
}
