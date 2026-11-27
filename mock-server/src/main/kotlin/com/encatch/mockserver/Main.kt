package com.encatch.mockserver

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode

/**
 * Stands in for both the Encatch backend API and the hosted form WebView page, so every SDK
 * variant/sample app can be driven end-to-end (init -> show form -> submit) fully offline and
 * deterministically. Point a sample app's `EncatchConfig(apiBaseUrl = ..., webHost = ...)` at
 * this server's address (both the same base, e.g. `http://10.0.2.2:8089` from an Android
 * emulator, `http://127.0.0.1:8089` from an iOS Simulator).
 *
 * Endpoint list mirrors `core/src/commonMain/kotlin/com/encatch/core/ApiClient.kt`'s `Endpoints`
 * object — every `EncatchApiClient.post()` call just needs a 2xx response with a parseable JSON
 * object body; most endpoints don't need specific fields (see `EncatchApiClient.post`'s handling
 * in `ApiClient.kt`), only `show-form`'s response needs `feedbackConfigurationId` since that
 * flows into `ShowFormResponse`/`ShowFormPayload`.
 *
 * For end-to-end checks (integrations/e2e) it also records every API call with its identity
 * headers: `GET /__mock/requests` returns them as JSON, `POST /__mock/reset` clears them.
 * identify-user answers with a user id derived from the name (`uid-<userName>`), as the real
 * server gives each user one.
 *
 * Secure identify, as the user gateway (React Native handover HANDOVER-ON-SIGNATURE-EXPIRED.md §3)
 * for a key with a secret and a session time, except that an identify without a signature is still
 * accepted (as a key without a secret), so the other flows (E13, E14) keep working:
 * an identify-user carrying `X-User-Signature` is checked against HMAC-SHA256(userName + time,
 * [MOCK_SECRET]) with the time from `X-User-Signature-Time` (else `X-User-Session-Time`). A wrong
 * signature, or a time that isn't 13-digit epoch ms or is over 5 minutes ahead, gets 401 with no
 * reason; a signature older than [SESSION_MINUTES] gets 401 with reason `user_signature_expired`.
 * `GET /__mock/sign?userName=&ageMinutes=` signs a time `ageMinutes` old (for the testers' E8
 * flow), and `POST /__mock/hooks` / `GET /__mock/hooks` record the app's onSignatureExpired calls.
 */
private const val DEFAULT_PORT = 8089

/** The mock API key's secret (mock only) and its session time, as a session key on the gateway. */
private const val MOCK_SECRET = "e2e-mock-secret"
private const val SESSION_MINUTES = 15L

private fun hmacHex(message: String): String {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(MOCK_SECRET.toByteArray(), "HmacSHA256"))
    return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** The gateway's answer to a signed identify: null when it passes, else the 401 body. */
private fun signatureRejection(userName: String?, signature: String?, time: String?): String? {
    if (signature.isNullOrEmpty()) return null
    if (userName == null || signature != hmacHex(userName + (time ?: ""))) return """{"error":"invalid api key"}"""
    if (time == null) return null
    val ms = time.takeIf { it.length == 13 && it.all { c -> c in '0'..'9' } }?.toLong() ?: return """{"error":"invalid api key"}"""
    val now = System.currentTimeMillis()
    if (ms > now + 5 * 60_000) return """{"error":"invalid api key"}"""
    // whole minutes, as the gateway
    if ((now - ms) / 60_000 > SESSION_MINUTES) return """{"error":"invalid api key","reason":"user_signature_expired"}"""
    return null
}

/** The app's onSignatureExpired reports (E8). */
private val hookCalls = java.util.concurrent.CopyOnWriteArrayList<String>()

/** One recorded API call: the endpoint, the headers the identity and session checks need, and (identify-user) the body. */
private data class Recorded(
    val endpoint: String,
    val headers: Map<String, String?>,
    val atMs: Long,
    val body: String? = null,
    val status: Int = 200,
    /** A 401's response body (it carries the gateway's `reason`). */
    val response: String? = null,
)

private val recorded = java.util.concurrent.CopyOnWriteArrayList<Recorded>()
private val recordedHeaders = listOf(
    "X-Session-Id", "X-User-Name", "X-User-Id", "X-User-Signature", "X-User-Signature-Time", "X-User-Session-Time", "X-Device-Id",
)

private fun io.ktor.server.application.ApplicationCall.record(body: String? = null, status: Int = 200, response: String? = null) {
    val endpoint = request.local.uri.substringBefore('?').substringAfterLast('/')
    recorded += Recorded(endpoint, recordedHeaders.associateWith { request.headers[it] }, System.currentTimeMillis(), body, status, response)
}

private fun jsonString(value: String?): String =
    if (value == null) "null" else kotlinx.serialization.json.JsonPrimitive(value).toString()

fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        install(ContentNegotiation) { json() }
        install(CallLogging)

        val formHtml = requireNotNull(object {}.javaClass.getResource("/react-native-sdk-form.html")) {
            "react-native-sdk-form.html missing from mock-server resources"
        }.readText()

        routing {
            // Matches buildFormWebViewUrl's "$webHost/s/react-native-sdk-form" exactly (no
            // extension, query params carry formId/ts/debug/presentation).
            get("/s/react-native-sdk-form") {
                call.respondText(formHtml, ContentType.Text.Html, HttpStatusCode.OK)
            }

            get("/__mock/requests") {
                val items = recorded.joinToString(",") { r ->
                    val headers = r.headers.entries.joinToString(",") { "${jsonString(it.key)}:${jsonString(it.value)}" }
                    val body = r.body?.let { ",\"body\":$it" } ?: ""
                    val response = r.response?.let { ",\"response\":$it" } ?: ""
                    """{"endpoint":${jsonString(r.endpoint)},"atMs":${r.atMs},"status":${r.status},"headers":{$headers}$body$response}"""
                }
                call.respondJson("[$items]")
            }
            post("/__mock/reset") {
                recorded.clear()
                hookCalls.clear()
                call.respondOk()
            }
            get("/__mock/sign") {
                val userName = call.request.queryParameters["userName"].orEmpty()
                val ageMinutes = call.request.queryParameters["ageMinutes"]?.toLongOrNull() ?: 0L
                val time = (System.currentTimeMillis() - ageMinutes * 60_000).toString()
                call.respondJson("""{"signature":${jsonString(hmacHex(userName + time))},"generatedDateTimeInUtc":${jsonString(time)}}""")
            }
            post("/__mock/hooks") {
                // JSON objects only: the list is served as a JSON array
                call.receiveText().takeIf { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }.isSuccess }
                    ?.let { hookCalls += it }
                call.respondOk()
            }
            get("/__mock/hooks") {
                call.respondJson("[${hookCalls.joinToString(",")}]")
            }

            post("/engage-product/encatch/api/v2/encatch/show-form") {
                call.record()
                call.receiveText()
                call.respondJson("""{"feedbackConfigurationId":"mock-config-1","appearanceProperties":{}}""")
            }
            post("/engage-product/encatch/api/v2/encatch/identify-user") {
                val body = call.receiveText()
                val name = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(body)
                        .let { it as kotlinx.serialization.json.JsonObject }["userName"]
                        ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                }.getOrNull()
                val headers = call.request.headers
                // the gateway checks the signature against X-User-Name (identify-user sends its own name)
                val rejection = signatureRejection(
                    headers["X-User-Name"],
                    headers["X-User-Signature"],
                    headers["X-User-Signature-Time"]?.takeIf { it.isNotBlank() } ?: headers["X-User-Session-Time"],
                )
                // the body too (it is JSON): the dedupe checks tell plain, traits and property updates apart
                call.record(body.takeIf { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.isSuccess }, if (rejection != null) 401 else 200, rejection)
                if (rejection != null) {
                    call.respondText(rejection, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                } else {
                    call.respondJson(if (name != null) """{"userId":${jsonString("uid-$name")}}""" else "{}")
                }
            }
            post("/engage-product/encatch/api/v2/encatch/track-event") { call.record(); call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/track-screen") { call.record(); call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/dismiss-form") { call.record(); call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/ping") { call.record(); call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/submit-form") { call.record(); call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/refine-text") {
                call.record()
                call.receiveText()
                call.respondJson("""{"refinedText":"mock refined text"}""")
            }
            post("/engage-product/encatch/api/v2/encatch/upload") {
                call.record()
                call.receiveText()
                call.respondJson("""{"fileUrl":"http://localhost:$port/mock-uploaded-file"}""")
            }
        }
    }.start(wait = true)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondOk() = respondJson("{}")

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: String) =
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
