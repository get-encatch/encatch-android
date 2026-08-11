package com.encatch.core

import com.russhwolf.settings.Settings

/** Creates the platform-backed [Settings] instance used by [EncatchStorage]. */
internal expect fun createEncatchSettings(): Settings
