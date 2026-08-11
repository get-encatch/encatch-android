package com.encatch.composetester

import android.content.Context
import kotlinx.serialization.json.Json

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

    actual var interceptorFormId: String?
        get() = prefs.getString("interceptor_form_id", null)
        set(value) {
            prefs.edit().putString("interceptor_form_id", value).apply()
        }

    actual var environment: TesterEnvironment
        get() = TesterEnvironment.fromName(prefs.getString("environment", null))
        set(value) {
            prefs.edit().putString("environment", value.name).apply()
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

actual object TestUsersStore {
    private val prefs get() = appContext.getSharedPreferences("encatch_compose_tester_users", Context.MODE_PRIVATE)

    actual fun list(): List<TestUser> {
        val raw = prefs.getString("saved_users", null) ?: return emptyList()
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
        prefs.edit().putString("saved_users", Json.encodeToString(users)).apply()
    }
}
