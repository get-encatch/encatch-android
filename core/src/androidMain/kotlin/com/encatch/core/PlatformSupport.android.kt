package com.encatch.core

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal actual fun createDefaultEngine(): HttpClientEngine = OkHttp.create()

internal actual fun isoStringFromEpochMillis(epochMillis: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(epochMillis))
}

internal actual suspend fun readContentUri(uri: String): ByteArray = withContext(Dispatchers.IO) {
    val context = EncatchAndroidContext.applicationContext
    context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { it.readBytes() }
        ?: error("[Encatch] Unable to open content URI: $uri")
}
