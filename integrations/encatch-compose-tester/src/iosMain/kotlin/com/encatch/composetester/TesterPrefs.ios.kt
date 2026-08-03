package com.encatch.composetester

import platform.Foundation.NSUserDefaults

actual object TesterPrefs {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual var apiKey: String?
        get() = defaults.stringForKey("api_key")
        set(value) = defaults.setObject(value, forKey = "api_key")

    actual var formId: String?
        get() = defaults.stringForKey("form_id")
        set(value) = defaults.setObject(value, forKey = "form_id")

    actual var apiBaseUrl: String?
        get() = defaults.stringForKey("api_base_url")
        set(value) = defaults.setObject(value, forKey = "api_base_url")

    actual var webHost: String?
        get() = defaults.stringForKey("web_host")
        set(value) = defaults.setObject(value, forKey = "web_host")

    actual var userName: String?
        get() = defaults.stringForKey("user_name")
        set(value) = defaults.setObject(value, forKey = "user_name")

    actual val isSetupComplete: Boolean
        get() = !apiKey.isNullOrBlank() && !formId.isNullOrBlank()

    actual fun clear() {
        listOf("api_key", "form_id", "api_base_url", "web_host", "user_name").forEach {
            defaults.removeObjectForKey(it)
        }
    }
}
