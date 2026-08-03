package com.encatch.composetester

import android.content.Context

/** Set once from [MainActivity] before first use — see that file's `onCreate`. */
internal lateinit var appContext: Context

actual object TesterPrefs {
    private val prefs get() = appContext.getSharedPreferences("encatch_compose_tester", Context.MODE_PRIVATE)

    actual var apiKey: String?
        get() = prefs.getString("api_key", null)
        set(value) {
            prefs.edit().putString("api_key", value).apply()
        }

    actual var formId: String?
        get() = prefs.getString("form_id", null)
        set(value) {
            prefs.edit().putString("form_id", value).apply()
        }

    actual var apiBaseUrl: String?
        get() = prefs.getString("api_base_url", null)
        set(value) {
            prefs.edit().putString("api_base_url", value).apply()
        }

    actual var webHost: String?
        get() = prefs.getString("web_host", null)
        set(value) {
            prefs.edit().putString("web_host", value).apply()
        }

    actual var userName: String?
        get() = prefs.getString("user_name", null)
        set(value) {
            prefs.edit().putString("user_name", value).apply()
        }

    actual val isSetupComplete: Boolean
        get() = !apiKey.isNullOrBlank() && !formId.isNullOrBlank()

    actual fun clear() {
        prefs.edit().clear().apply()
    }
}
