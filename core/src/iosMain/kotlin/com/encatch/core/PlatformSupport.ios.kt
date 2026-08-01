package com.encatch.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

internal actual fun createDefaultEngine(): HttpClientEngine = Darwin.create()

/** Seconds between the Unix epoch (1970-01-01) and the NSDate reference date (2001-01-01). */
private const val NS_TIME_INTERVAL_SINCE_1970_OFFSET = 978307200.0

internal actual fun isoStringFromEpochMillis(epochMillis: Long): String {
    val formatter = NSISO8601DateFormatter()
    val date = NSDate(timeIntervalSinceReferenceDate = epochMillis / 1000.0 - NS_TIME_INTERVAL_SINCE_1970_OFFSET)
    return formatter.stringFromDate(date)
}

/**
 * There's no Android-style `content://` URI concept on iOS — this path is Android-only.
 * iOS upload flows must supply raw bytes via [UploadFileSource.Bytes] instead.
 */
internal actual suspend fun readContentUri(uri: String): ByteArray =
    throw UnsupportedOperationException(
        "readContentUri is Android-only; supply UploadFileSource.Bytes on iOS instead (uri=$uri)",
    )
