package com.encatch.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import java.time.Instant
import java.time.format.DateTimeFormatter

internal actual fun createDefaultEngine(): HttpClientEngine = CIO.create()

internal actual fun isoStringFromEpochMillis(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

/**
 * The JVM desktop target has no `content://` URI concept — this path is Android-only.
 * Desktop upload flows must supply raw bytes via [UploadFileSource.Bytes] instead.
 */
internal actual suspend fun readContentUri(uri: String): ByteArray =
    throw UnsupportedOperationException(
        "readContentUri is Android-only; supply UploadFileSource.Bytes on desktop instead (uri=$uri)",
    )
