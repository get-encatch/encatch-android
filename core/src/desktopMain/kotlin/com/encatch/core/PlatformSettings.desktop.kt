package com.encatch.core

import com.russhwolf.settings.PreferencesSettings
import com.russhwolf.settings.Settings
import java.util.prefs.Preferences

internal actual fun createEncatchSettings(): Settings =
    PreferencesSettings(Preferences.userRoot().node("com.encatch.core"))
