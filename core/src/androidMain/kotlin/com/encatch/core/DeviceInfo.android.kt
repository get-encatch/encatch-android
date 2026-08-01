package com.encatch.core

import android.os.Build
import java.util.Locale
import java.util.TimeZone

internal actual fun collectPlatformDeviceFacts(): PlatformDeviceFacts {
    val context = EncatchAndroidContext.applicationContext

    val appVersion = runCatching {
        val pm = context.packageManager
        val packageInfo = pm.getPackageInfo(context.packageName, 0)
        packageInfo.versionName
    }.getOrNull()

    return PlatformDeviceFacts(
        osVersion = Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString(),
        deviceLocale = Locale.getDefault().toLanguageTag(),
        timezone = runCatching { TimeZone.getDefault().id }.getOrNull(),
        appVersion = appVersion,
        appPackageName = context.packageName,
    )
}
