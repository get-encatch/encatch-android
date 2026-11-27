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
 */
private const val DEFAULT_PORT = 8089

/** One recorded API call: the endpoint, the headers the identity and session checks need, and (identify-user) the body. */
private data class Recorded(val endpoint: String, val headers: Map<String, String?>, val atMs: Long, val body: String? = null)

private val recorded = java.util.concurrent.CopyOnWriteArrayList<Recorded>()
private val recordedHeaders = listOf("X-Session-Id", "X-User-Name", "X-User-Id", "X-User-Signature", "X-Device-Id")

private fun io.ktor.server.application.ApplicationCall.record(body: String? = null) {
    val endpoint = request.local.uri.substringBefore('?').substringAfterLast('/')
    recorded += Recorded(endpoint, recordedHeaders.associateWith { request.headers[it] }, System.currentTimeMillis(), body)
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
                    """{"endpoint":${jsonString(r.endpoint)},"atMs":${r.atMs},"headers":{$headers}$body}"""
                }
                call.respondJson("[$items]")
            }
            post("/__mock/reset") {
                recorded.clear()
                call.respondOk()
            }

            post("/engage-product/encatch/api/v2/encatch/show-form") {
                call.record()
                call.receiveText()
                call.respondJson("""{"feedbackConfigurationId":"mock-config-1","appearanceProperties":{}}""")
            }
            post("/engage-product/encatch/api/v2/encatch/identify-user") {
                val body = call.receiveText()
                // the body too (it is JSON): the dedupe checks tell plain, traits and property updates apart
                call.record(body.takeIf { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) }.isSuccess })
                val name = runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(body)
                        .let { it as kotlinx.serialization.json.JsonObject }["userName"]
                        ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }
                }.getOrNull()
                call.respondJson(if (name != null) """{"userId":${jsonString("uid-$name")}}""" else "{}")
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
