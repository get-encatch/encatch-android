package com.encatch.core

import java.util.Locale
import java.util.TimeZone

internal actual val SDK_PLATFORM: String = "desktop"

/**
 * There's no OS-level equivalent of an Android package name/app version on the JVM desktop
 * target — the host app's own build tooling would need to supply these. Left null until a
 * concrete desktop consumer needs them.
 */
internal actual fun collectPlatformDeviceFacts(): PlatformDeviceFacts = PlatformDeviceFacts(
    osVersion = System.getProperty("os.name") + " " + System.getProperty("os.version"),
    deviceLocale = Locale.getDefault().toLanguageTag(),
    timezone = runCatching { TimeZone.getDefault().id }.getOrNull(),
    appVersion = null,
    appPackageName = null,
)
