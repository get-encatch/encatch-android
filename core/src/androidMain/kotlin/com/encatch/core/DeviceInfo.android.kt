package com.encatch.core

import android.os.Build
import java.util.Locale
import java.util.TimeZone

internal actual val SDK_PLATFORM: String = "android"

internal actual fun collectPlatformDeviceFacts(): PlatformDeviceFacts {
    val context = EncatchAndroidContext.applicationContext

    val appVersion = runCatching {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, 0)
        packageInfo.versionName
    }.getOrNull()

    return PlatformDeviceFacts(
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        // ISO 639-1 language code only (e.g. "en"), not `toLanguageTag()`'s full "en-IN"-style
        // region-qualified BCP-47 tag — the API's `$deviceLanguage`/`$userLanguage` fields
        // require plain ISO 639-1.
        deviceLocale = Locale.getDefault().language,
        timezone = runCatching { TimeZone.getDefault().id }.getOrNull(),
        appVersion = appVersion,
        appPackageName = context.packageName,
    )
}
