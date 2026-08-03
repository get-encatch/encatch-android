package com.encatch.kmptester

import android.content.Context

/** Local persistence for the setup screen, so one APK works for any tester/environment. */
class TesterPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("encatch_kmp_tester", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API_KEY, null)
        set(value) = prefs.edit().putString(KEY_API_KEY, value).apply()

    var formId: String?
        get() = prefs.getString(KEY_FORM_ID, null)
        set(value) = prefs.edit().putString(KEY_FORM_ID, value).apply()

    var apiBaseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var webHost: String?
        get() = prefs.getString(KEY_WEB_HOST, null)
        set(value) = prefs.edit().putString(KEY_WEB_HOST, value).apply()

    var interceptorFormId: String?
        get() = prefs.getString(KEY_INTERCEPTOR_FORM_ID, null)
        set(value) = prefs.edit().putString(KEY_INTERCEPTOR_FORM_ID, value).apply()

    var userName: String?
        get() = prefs.getString(KEY_USER_NAME, null)
        set(value) = prefs.edit().putString(KEY_USER_NAME, value).apply()

    val isSetupComplete: Boolean
        get() = !apiKey.isNullOrBlank() && !formId.isNullOrBlank()

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_API_KEY = "api_key"
        const val KEY_FORM_ID = "form_id"
        const val KEY_BASE_URL = "api_base_url"
        const val KEY_WEB_HOST = "web_host"
        const val KEY_INTERCEPTOR_FORM_ID = "interceptor_form_id"
        const val KEY_USER_NAME = "user_name"
    }
}
