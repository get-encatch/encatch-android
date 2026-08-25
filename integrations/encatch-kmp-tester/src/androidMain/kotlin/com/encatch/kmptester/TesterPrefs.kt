package com.encatch.kmptester

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Environment presets for the Setup screen — a pure tester-app convenience, not an SDK concept. */
enum class TesterEnvironment(val label: String, val apiBaseUrl: String, val webHost: String) {
    DEV("Dev", "https://api.dev.encatch.com", "https://form.dev.encatch.com"),
    UAT("UAT", "https://api.uat.encatch.com", "https://form-uat.encatch.com"),
    PROD("Prod", "https://api.encatch.com", "https://form.encatch.com"),
    ;

    companion object {
        fun fromName(value: String?): TesterEnvironment = entries.find { it.name == value } ?: DEV
    }
}

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

    var environment: TesterEnvironment
        get() = TesterEnvironment.fromName(prefs.getString(KEY_ENVIRONMENT, null))
        set(value) = prefs.edit().putString(KEY_ENVIRONMENT, value.name).apply()

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
        const val KEY_ENVIRONMENT = "environment"
        const val KEY_USER_NAME = "user_name"
    }
}

/** A locally-saved test identity — independent of SDK identify, lets testers switch users without retyping. */
@Serializable
data class TestUser(
    val username: String,
    val email: String = "",
    val displayName: String = "",
)

/** SharedPreferences-backed JSON list of [TestUser]s, so testers can save/select/edit multiple identities. */
class TestUsersStore(context: Context) {
    private val prefs = context.getSharedPreferences("encatch_kmp_tester_users", Context.MODE_PRIVATE)

    fun list(): List<TestUser> {
        val raw = prefs.getString(KEY_USERS, null) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<TestUser>>(raw) }.getOrDefault(emptyList())
    }

    fun add(user: TestUser) {
        val updated = list().filterNot { it.username == user.username } + user
        save(updated)
    }

    fun update(user: TestUser) {
        val updated = list().map { if (it.username == user.username) user else it }
        save(updated)
    }

    private fun save(users: List<TestUser>) {
        prefs.edit().putString(KEY_USERS, Json.encodeToString(users)).apply()
    }

    private companion object {
        const val KEY_USERS = "saved_users"
    }
}
