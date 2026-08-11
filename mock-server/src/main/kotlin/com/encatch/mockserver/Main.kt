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
 */
private const val DEFAULT_PORT = 8089

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

            post("/engage-product/encatch/api/v2/encatch/show-form") {
                call.receiveText()
                call.respondJson("""{"feedbackConfigurationId":"mock-config-1","appearanceProperties":{}}""")
            }
            post("/engage-product/encatch/api/v2/encatch/identify-user") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/track-event") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/track-screen") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/dismiss-form") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/ping") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/submit-form") { call.respondOk() }
            post("/engage-product/encatch/api/v2/encatch/refine-text") {
                call.receiveText()
                call.respondJson("""{"refinedText":"mock refined text"}""")
            }
            post("/engage-product/encatch/api/v2/encatch/upload") {
                call.receiveText()
                call.respondJson("""{"fileUrl":"http://localhost:$port/mock-uploaded-file"}""")
            }
        }
    }.start(wait = true)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondOk() = respondJson("{}")

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(body: String) =
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
