package com.encatch.core

import java.util.logging.Level
import java.util.logging.Logger

private val logger = Logger.getLogger("Encatch")

internal actual fun platformLog(tag: String, level: LogLevel, message: String) {
    runCatching {
        when (level) {
            LogLevel.DEBUG -> logger.log(Level.FINE, "[$tag] $message")
            LogLevel.WARN -> logger.log(Level.WARNING, "[$tag] $message")
        }
    }
}
