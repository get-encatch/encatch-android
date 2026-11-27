package com.encatch.androidtester

import com.encatch.core.SecureOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Secure identify against the mock-server (E8, onSignatureExpired): it signs like the app's own
 * server would (`/__mock/sign`, a time `ageMinutes` old) and records the onSignatureExpired calls
 * (`/__mock/hooks`). Only with the Mock environment ([baseUrl] null otherwise: nothing is sent);
 * a failure is logged and gives null.
 */
object MockSignature {
    /** The mock-server's base URL, set at init with the Mock environment only. */
    @Volatile var baseUrl: String? = null

    suspend fun sign(userName: String, ageMinutes: Int): SecureOptions? = withContext(Dispatchers.IO) {
        runCatching {
            val base = baseUrl ?: return@runCatching null
            val url = URL("$base/__mock/sign?userName=${URLEncoder.encode(userName, "UTF-8")}&ageMinutes=$ageMinutes")
            val text = (url.openConnection() as HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 5_000
                inputStream.bufferedReader().use { it.readText() }
            }
            val json = Json.parseToJsonElement(text) as JsonObject
            SecureOptions(json["signature"]!!.jsonPrimitive.content, json["generatedDateTimeInUtc"]!!.jsonPrimitive.content)
        }.onFailure { Log.w(TAG, "mock sign failed: $it") }.getOrNull()
    }

    suspend fun reportExpired(userName: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val base = baseUrl ?: return@runCatching
                (URL("$base/__mock/hooks").openConnection() as HttpURLConnection).run {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 5_000
                    readTimeout = 5_000
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write("""{"hook":"onSignatureExpired","userName":${JsonPrimitive(userName)}}""".toByteArray()) }
                    responseCode
                }
            }.onFailure { Log.w(TAG, "mock hooks report failed: $it") }
        }
    }

    private const val TAG = "EncatchTester"
}
