package com.encatch.composetester

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    actual var interceptorFormId: String?
        get() = defaults.stringForKey("interceptor_form_id")
        set(value) = defaults.setObject(value, forKey = "interceptor_form_id")

    actual var environment: TesterEnvironment
        get() = TesterEnvironment.fromName(defaults.stringForKey("environment"))
        set(value) = defaults.setObject(value.name, forKey = "environment")

    actual var userName: String?
        get() = defaults.stringForKey("user_name")
        set(value) = defaults.setObject(value, forKey = "user_name")

    actual val isSetupComplete: Boolean
        get() = !apiKey.isNullOrBlank() && !formId.isNullOrBlank()

    actual fun clear() {
        listOf("api_key", "form_id", "api_base_url", "web_host", "interceptor_form_id", "user_name").forEach {
            defaults.removeObjectForKey(it)
        }
    }
}

actual object TestUsersStore {
    private val defaults = NSUserDefaults.standardUserDefaults
    private const val KEY = "saved_users"

    actual fun list(): List<TestUser> {
        val raw = defaults.stringForKey(KEY) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<TestUser>>(raw) }.getOrDefault(emptyList())
    }

    actual fun add(user: TestUser) {
        val updated = list().filterNot { it.username == user.username } + user
        save(updated)
    }

    actual fun update(user: TestUser) {
        val updated = list().map { if (it.username == user.username) user else it }
        save(updated)
    }

    private fun save(users: List<TestUser>) {
        defaults.setObject(Json.encodeToString(users), forKey = KEY)
    }
}
