package com.encatch.core

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Key-value persistence layer, mirroring `storage.ts` from the RN SDK. All keys are
 * namespaced under `@encatch/`. Backed by [Settings] (SharedPreferences on Android).
 */
class EncatchStorage internal constructor(
    private val settings: Settings = createEncatchSettings(),
) {
    companion object {
        private const val KEY_DEVICE_ID = "@encatch/device_id"
        private const val KEY_USER_NAME = "@encatch/user_name"
        private const val KEY_USER_ID_PREFIX = "@encatch/user_id_"
        private const val KEY_FT_PREFIX = "@encatch/ft_"
        private const val KEY_PREFERENCES = "@encatch/preferences"
        private const val KEY_SESSION_STOPPED = "@encatch/session_stopped"
        private const val KEY_RETRY_QUEUE = "@encatch/retry_queue"
    }

    // Session ID is in-memory only — reset when the app process ends.
    private var inMemorySessionId: String? = null

    fun getOrCreateDeviceId(): String {
        val stored = runCatching { settings.getStringOrNull(KEY_DEVICE_ID) }.getOrNull()
        if (stored != null) return stored
        val id = uuidV7()
        runCatching { settings[KEY_DEVICE_ID] = id }
        return id
    }

    fun getOrCreateSessionId(): String {
        inMemorySessionId?.let { return it }
        val id = uuidV7()
        inMemorySessionId = id
        return id
    }

    fun clearSession() {
        inMemorySessionId = null
    }

    fun getUserName(): String? = runCatching { settings.getStringOrNull(KEY_USER_NAME) }.getOrNull()

    fun setUserName(name: String) {
        runCatching { settings[KEY_USER_NAME] = name }
    }

    fun clearUserName() {
        runCatching { settings.remove(KEY_USER_NAME) }
    }

    fun getUserId(userName: String): String? =
        runCatching { settings.getStringOrNull(KEY_USER_ID_PREFIX + userName) }.getOrNull()

    fun setUserId(userName: String, userId: String) {
        runCatching { settings[KEY_USER_ID_PREFIX + userName] = userId }
    }

    fun clearUserId(userName: String) {
        runCatching { settings.remove(KEY_USER_ID_PREFIX + userName) }
    }

    private fun ftKey(identityKey: String) = KEY_FT_PREFIX + identityKey

    fun getFeedbackTransactions(identityKey: String): String? =
        runCatching { settings.getStringOrNull(ftKey(identityKey)) }.getOrNull()

    fun setFeedbackTransactions(identityKey: String, value: String) {
        runCatching { settings[ftKey(identityKey)] = value }
    }

    fun clearFeedbackTransactions(identityKey: String) {
        runCatching { settings.remove(ftKey(identityKey)) }
    }

    data class Preferences(val locale: String? = null, val country: String? = null)

    fun getPreferences(): Preferences {
        val raw = runCatching { settings.getStringOrNull(KEY_PREFERENCES) }.getOrNull() ?: return Preferences()
        return runCatching {
            val map = Json.decodeFromString<Map<String, String>>(raw)
            Preferences(locale = map["locale"], country = map["country"])
        }.getOrDefault(Preferences())
    }

    fun setPreferences(locale: String? = null, country: String? = null) {
        val current = getPreferences()
        val merged = Preferences(
            locale = locale ?: current.locale,
            country = country ?: current.country,
        )
        val map = buildMap {
            merged.locale?.let { put("locale", it) }
            merged.country?.let { put("country", it) }
        }
        runCatching { settings[KEY_PREFERENCES] = Json.encodeToString(map) }
    }

    fun clearPreferences() {
        runCatching { settings.remove(KEY_PREFERENCES) }
    }

    fun getSessionStopped(): Boolean =
        runCatching { settings.getStringOrNull(KEY_SESSION_STOPPED) }.getOrNull() == "true"

    fun setSessionStopped() {
        runCatching { settings[KEY_SESSION_STOPPED] = "true" }
    }

    fun clearSessionStopped() {
        runCatching { settings.remove(KEY_SESSION_STOPPED) }
    }

    fun getRetryQueueRaw(): String? = runCatching { settings.getStringOrNull(KEY_RETRY_QUEUE) }.getOrNull()

    fun setRetryQueueRaw(json: String) {
        runCatching { settings[KEY_RETRY_QUEUE] = json }
    }

    fun clearRetryQueue() {
        runCatching { settings.remove(KEY_RETRY_QUEUE) }
    }

    /** Wipes every `@encatch/`-namespaced key — used by [Encatch.clearAll]. */
    fun clearAll() {
        runCatching {
            settings.remove(KEY_DEVICE_ID)
            settings.remove(KEY_USER_NAME)
            settings.remove(KEY_PREFERENCES)
            settings.remove(KEY_SESSION_STOPPED)
            settings.remove(KEY_RETRY_QUEUE)
            settings.keys.filter { it.startsWith(KEY_USER_ID_PREFIX) || it.startsWith(KEY_FT_PREFIX) }
                .forEach { settings.remove(it) }
        }
        inMemorySessionId = null
    }
}
