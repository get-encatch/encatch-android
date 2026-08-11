package com.encatch.core

import android.content.Context
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings

private const val PREFS_NAME = "encatch_prefs"

internal actual fun createEncatchSettings(): Settings {
    check(EncatchAndroidContext.isAttached) {
        "Encatch: application context not attached yet. This is captured automatically via " +
            "AndroidX Startup and should be available before Encatch.init() is called."
    }
    val prefs = EncatchAndroidContext.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return SharedPreferencesSettings(prefs)
}
