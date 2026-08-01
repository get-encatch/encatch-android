package com.encatch.core

import com.russhwolf.settings.NSUserDefaultsSettings
import com.russhwolf.settings.Settings
import platform.Foundation.NSUserDefaults

internal actual fun createEncatchSettings(): Settings =
    NSUserDefaultsSettings(NSUserDefaults.standardUserDefaults)
