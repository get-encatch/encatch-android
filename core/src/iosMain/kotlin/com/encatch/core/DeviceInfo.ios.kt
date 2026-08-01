package com.encatch.core

import platform.Foundation.NSBundle
import platform.Foundation.NSLocale
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.localTimeZone
import platform.UIKit.UIDevice

internal actual val SDK_PLATFORM: String = "ios"

internal actual fun collectPlatformDeviceFacts(): PlatformDeviceFacts {
    val bundle = NSBundle.mainBundle
    val appVersion = bundle.infoDictionary?.get("CFBundleShortVersionString") as? String
    val appPackageName = bundle.bundleIdentifier

    return PlatformDeviceFacts(
        osVersion = UIDevice.currentDevice.systemVersion,
        deviceLocale = NSLocale.currentLocale.localeIdentifier,
        timezone = NSTimeZone.localTimeZone.name,
        appVersion = appVersion,
        appPackageName = appPackageName,
    )
}
