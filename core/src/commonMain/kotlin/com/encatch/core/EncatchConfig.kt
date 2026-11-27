package com.encatch.core

const val DEFAULT_API_BASE_URL = "https://api.encatch.com"
const val DEFAULT_WEB_HOST = "https://form.encatch.com"

/**
 * Optional interceptor called before any form is shown (manual or automatic).
 * If it returns false, the SDK form will not open; the host app can show a
 * custom widget using the payload. Prefills are cleared when false.
 */
typealias BeforeShowFormInterceptor = suspend (ShowFormInterceptorPayload) -> Boolean

/** Passed to [EncatchConfig.onSignatureExpired]: the user whose secure-identify signature expired. */
data class SignatureExpiredInfo(val userName: String)

/** See [EncatchConfig.onSignatureExpired]. */
typealias SignatureExpiredListener = suspend (SignatureExpiredInfo) -> Unit

data class EncatchConfig(
    /** Base URL used for all API calls. Defaults to [DEFAULT_API_BASE_URL]. */
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
    /** Base URL used to load the hosted form WebView page. Defaults to [DEFAULT_WEB_HOST]. */
    val webHost: String = DEFAULT_WEB_HOST,
    /** Default theme for forms. Defaults to [Theme.SYSTEM]. */
    val theme: Theme = Theme.SYSTEM,
    /** When true, the form overlay is displayed full-screen. */
    val isFullScreen: Boolean = false,
    /** Enable verbose SDK logging. */
    val debugMode: Boolean = false,
    /** Override app version (default: auto-detected from the host app's PackageInfo). */
    val appVersion: String? = null,
    /**
     * Keep the identified user after an app restart (the default). When false, the user (name, user
     * id, secure signature, survey history) lives in memory for the current app run only: the next
     * launch starts as a visitor until the app identifies again, and user data an earlier run saved is
     * deleted at [Encatch.init]. The device id, a saved `stopSession()` and the locale and country
     * preferences are still saved.
     */
    val persistUserAcrossRestarts: Boolean = true,
    /**
     * Called when the current user's secure-identify signature has expired, so the app can get a
     * fresh one from its own server and call [Encatch.identifyUser] again with it. A rejected new
     * signature tells it once per user until an identify for them succeeds (a signature that keeps
     * being rejected doesn't loop); the stored signature expiring always does, so at most twice. Called
     * after the SDK has handled the reply, and only while that user is still the current one. May be
     * called on any thread; an exception it throws is logged and otherwise ignored.
     *
     * The SDK only learns of the expiry when a request the API checks goes out (today identify-user:
     * [Encatch.identifyUser] and [Encatch.setUserProperties]); it can't warn at launch. The signed
     * time (`generatedDateTimeInUtc`) must be epoch milliseconds (13 digits) from a server clock
     * within 5 minutes of real time.
     */
    val onSignatureExpired: SignatureExpiredListener? = null,
    val onBeforeShowForm: BeforeShowFormInterceptor? = null,
)
