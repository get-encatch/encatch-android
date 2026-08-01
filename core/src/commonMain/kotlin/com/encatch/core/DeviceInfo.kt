package com.encatch.core

internal const val SDK_VERSION = "0.1.0"
internal const val SDK_PLATFORM = "android"

/** Platform-sourced device facts, assembled into [ApiDeviceInfo] by [Encatch]. */
internal data class PlatformDeviceFacts(
    val osVersion: String,
    val deviceLocale: String,
    val timezone: String?,
    val appVersion: String?,
    val appPackageName: String?,
)

internal expect fun collectPlatformDeviceFacts(): PlatformDeviceFacts
