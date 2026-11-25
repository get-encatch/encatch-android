package com.encatch.core

import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Key-value persistence layer, mirroring `storage.ts` from the RN SDK. All keys are
 * namespaced under `@encatch/`. Backed by [Settings] (SharedPreferences on Android).
 *
 * The user's data (user name, user ids, secure signatures, survey history) goes through [userStore]:
 * with `persistUserAcrossRestarts` (the default) it is [settings], so it survives a restart; without
 * it, an in-memory store that lasts for the current app run only (RN 1.5.0 item 4). Everything else
 * (device id, a saved stopSession(), preferences, the retry queue's labels) is always saved.
 */
class EncatchStorage internal constructor(
    private val settings: Settings = createEncatchSettings(),
    private val userStore: Settings = settings,
) {
    companion object {
        private const val KEY_DEVICE_ID = "@encatch/device_id"
        private const val KEY_USER_NAME = "@encatch/user_name"
        private const val KEY_USER_ID_PREFIX = "@encatch/user_id_"
        private const val KEY_USER_SIGNATURE_PREFIX = "@encatch/user_signature_"
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

    fun getUserName(): String? = runCatching { userStore.getStringOrNull(KEY_USER_NAME) }.getOrNull()

    fun setUserName(name: String) {
        runCatching { userStore[KEY_USER_NAME] = name }
    }

    fun clearUserName() {
        runCatching { userStore.remove(KEY_USER_NAME) }
    }

    fun getUserId(userName: String): String? =
        runCatching { userStore.getStringOrNull(KEY_USER_ID_PREFIX + userName) }.getOrNull()

    fun setUserId(userName: String, userId: String) {
        runCatching { userStore[KEY_USER_ID_PREFIX + userName] = userId }
    }

    fun clearUserId(userName: String) {
        runCatching { userStore.remove(KEY_USER_ID_PREFIX + userName) }
    }

    /**
     * The secure-identify signature of [userName], kept after a successful secure identify and sent
     * on every request of that user, so it survives a restart (RN 1.4.4 item 16, `storage.ts`).
     */
    internal fun getUserSignature(userName: String): StoredSignature? {
        val raw = runCatching { userStore.getStringOrNull(KEY_USER_SIGNATURE_PREFIX + userName) }.getOrNull() ?: return null
        return runCatching {
            val map = Json.decodeFromString<Map<String, String?>>(raw)
            map["signature"]?.let { StoredSignature(it, map["time"]) }
        }.getOrNull()
    }

    internal fun setUserSignature(userName: String, value: StoredSignature) {
        val map = mapOf("signature" to value.signature, "time" to value.time)
        runCatching { userStore[KEY_USER_SIGNATURE_PREFIX + userName] = Json.encodeToString(map) }
    }

    internal fun clearUserSignature(userName: String) {
        runCatching { userStore.remove(KEY_USER_SIGNATURE_PREFIX + userName) }
    }

    private fun ftKey(identityKey: String) = KEY_FT_PREFIX + identityKey

    fun getFeedbackTransactions(identityKey: String): String? =
        runCatching { userStore.getStringOrNull(ftKey(identityKey)) }.getOrNull()

    fun setFeedbackTransactions(identityKey: String, value: String) {
        runCatching { userStore[ftKey(identityKey)] = value }
    }

    fun clearFeedbackTransactions(identityKey: String) {
        runCatching { userStore.remove(ftKey(identityKey)) }
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

    private fun isUserKey(key: String) = key == KEY_USER_NAME || key.startsWith(KEY_USER_ID_PREFIX) ||
        key.startsWith(KEY_FT_PREFIX) || key.startsWith(KEY_USER_SIGNATURE_PREFIX)

    /**
     * Deletes the user's data saved on the device (user name, user ids, signatures, survey history):
     * with `persistUserAcrossRestarts = false`, what an earlier run saved with it on is not kept.
     */
    fun deleteSavedUserData() {
        runCatching { settings.keys.filter(::isUserKey).forEach { settings.remove(it) } }
    }

    /** Wipes every `@encatch/`-namespaced key — used by [Encatch.clearAll]. */
    fun clearAll() {
        runCatching {
            settings.remove(KEY_DEVICE_ID)
            settings.remove(KEY_PREFERENCES)
            settings.remove(KEY_SESSION_STOPPED)
            settings.remove(KEY_RETRY_QUEUE)
        }
        deleteSavedUserData()
        if (userStore !== settings) runCatching { userStore.clear() }
        inMemorySessionId = null
    }
}

/**
 * The in-memory [Settings] that holds the user's data for the current app run when
 * `persistUserAcrossRestarts` is off: strings only (all the storage keeps), safe to use from several
 * coroutines at once (each write replaces the whole map).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class MemoryUserStore : Settings {
    private val map = AtomicReference(emptyMap<String, String>())

    private inline fun update(change: (Map<String, String>) -> Map<String, String>) {
        while (true) {
            val current = map.load()
            if (map.compareAndSet(current, change(current))) return
        }
    }

    override val keys: Set<String> get() = map.load().keys
    override val size: Int get() = map.load().size
    override fun clear() = update { emptyMap() }
    override fun remove(key: String) = update { it - key }
    override fun hasKey(key: String): Boolean = key in map.load()
    override fun putString(key: String, value: String) = update { it + (key to value) }
    override fun getString(key: String, defaultValue: String): String = map.load()[key] ?: defaultValue
    override fun getStringOrNull(key: String): String? = map.load()[key]

    private fun unsupported(): Nothing = throw UnsupportedOperationException("MemoryUserStore keeps strings only")
    override fun putInt(key: String, value: Int) = unsupported()
    override fun getInt(key: String, defaultValue: Int): Int = unsupported()
    override fun getIntOrNull(key: String): Int? = unsupported()
    override fun putLong(key: String, value: Long) = unsupported()
    override fun getLong(key: String, defaultValue: Long): Long = unsupported()
    override fun getLongOrNull(key: String): Long? = unsupported()
    override fun putFloat(key: String, value: Float) = unsupported()
    override fun getFloat(key: String, defaultValue: Float): Float = unsupported()
    override fun getFloatOrNull(key: String): Float? = unsupported()
    override fun putDouble(key: String, value: Double) = unsupported()
    override fun getDouble(key: String, defaultValue: Double): Double = unsupported()
    override fun getDoubleOrNull(key: String): Double? = unsupported()
    override fun putBoolean(key: String, value: Boolean) = unsupported()
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = unsupported()
    override fun getBooleanOrNull(key: String): Boolean? = unsupported()
}

/** A stored secure-identify signature; [time] is `generatedDateTimeInUtc` as given (epoch ms), when the key has a session time. */
internal data class StoredSignature(val signature: String, val time: String?)
