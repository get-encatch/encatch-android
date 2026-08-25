package com.encatch.composetester

import kotlinx.serialization.Serializable

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

/** Local persistence for the setup screen, so one build works for any tester/environment. */
expect object TesterPrefs {
    var apiKey: String?
    var formId: String?
    var apiBaseUrl: String?
    var webHost: String?
    var interceptorFormId: String?
    var environment: TesterEnvironment
    var userName: String?
    val isSetupComplete: Boolean
    fun clear()
}

/** A locally-saved test identity — independent of SDK identify, lets testers switch users without retyping. */
@Serializable
data class TestUser(
    val username: String,
    val email: String = "",
    val displayName: String = "",
)

/** Platform-persisted JSON list of [TestUser]s, so testers can save/select/edit multiple identities. */
expect object TestUsersStore {
    fun list(): List<TestUser>
    fun add(user: TestUser)
    fun update(user: TestUser)
}
