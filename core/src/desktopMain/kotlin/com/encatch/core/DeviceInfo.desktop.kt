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
    // ISO 639-1 language code only (e.g. "en"), not `toLanguageTag()`'s full "en-US"-style
    // region-qualified BCP-47 tag — the API's `$deviceLanguage`/`$userLanguage` fields require
    // plain ISO 639-1.
    deviceLocale = Locale.getDefault().language,
    timezone = runCatching { TimeZone.getDefault().id }.getOrNull(),
    appVersion = null,
    appPackageName = null,
)
