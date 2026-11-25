package com.encatch.core

const val DEFAULT_API_BASE_URL = "https://api.encatch.com"
const val DEFAULT_WEB_HOST = "https://form.encatch.com"

/**
 * Optional interceptor called before any form is shown (manual or automatic).
 * If it returns false, the SDK form will not open; the host app can show a
 * custom widget using the payload. Prefills are cleared when false.
 */
typealias BeforeShowFormInterceptor = suspend (ShowFormInterceptorPayload) -> Boolean

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
    val onBeforeShowForm: BeforeShowFormInterceptor? = null,
)
