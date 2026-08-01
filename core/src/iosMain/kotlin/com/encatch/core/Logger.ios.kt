package com.encatch.core

import platform.Foundation.NSLog

internal actual fun platformLog(tag: String, level: LogLevel, message: String) {
    runCatching {
        val prefix = when (level) {
            LogLevel.DEBUG -> "DEBUG"
            LogLevel.WARN -> "WARN"
        }
        NSLog("[$tag] $prefix: $message")
    }
}
