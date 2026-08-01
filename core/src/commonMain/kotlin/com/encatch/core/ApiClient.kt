package com.encatch.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Thrown for non-2xx responses, mirroring the RN SDK's `[Encatch API] <endpoint> failed with status <n>: <body>`. */
class EncatchApiException(
    val endpoint: String,
    val status: Int,
    val responseBody: String,
) : Exception("[Encatch API] $endpoint failed with status $status: $responseBody")

/** Thrown when a public method is called before [Encatch.init]. */
class EncatchNotInitializedException : IllegalStateException("[Encatch] SDK not initialized")

internal object Endpoints {
    const val IDENTIFY_USER = "engage-product/encatch/api/v2/encatch/identify-user"
    const val TRACK_EVENT = "engage-product/encatch/api/v2/encatch/track-event"
    const val TRACK_SCREEN = "engage-product/encatch/api/v2/encatch/track-screen"
    const val SHOW_FORM = "engage-product/encatch/api/v2/encatch/show-form"
    const val DISMISS_FORM = "engage-product/encatch/api/v2/encatch/dismiss-form"
    const val PING = "engage-product/encatch/api/v2/encatch/ping"
    const val REFINE_TEXT = "engage-product/encatch/api/v2/encatch/refine-text"
    const val SUBMIT_FORM = "engage-product/encatch/api/v2/encatch/submit-form"
    const val UPLOAD = "engage-product/encatch/api/v2/encatch/upload"
    const val QNA_WITH_AI_STREAM = "engage-product/encatch/api/v2/encatch/qna-with-ai/stream"
}

/** Common response metadata present on most Encatch API responses. */
internal data class ResponseMeta(
    val pingAgainIn: Double?,
    val pingOnNextPageVisit: Boolean?,
    val feedbackTransactions: String?,
    val userPendingRetryExhausted: Boolean,
)

internal fun JsonObject.toResponseMeta(): ResponseMeta = ResponseMeta(
    pingAgainIn = this["pingAgainIn"]?.jsonPrimitive?.doubleOrNull,
    pingOnNextPageVisit = this["pingOnNextPageVisit"]?.jsonPrimitive?.booleanOrNull,
    feedbackTransactions = this["\$feedbackTransactions"]?.jsonPrimitive?.contentOrNull,
    userPendingRetryExhausted = this["user_pending_retry_exhausted"]?.jsonPrimitive?.booleanOrNull ?: false,
)

/** Snapshot of auth-relevant SDK state, used to build request headers per call. */
internal data class AuthState(
    val apiKey: String?,
    val sessionId: String?,
    val userName: String?,
    val userId: String?,
    val userSignature: String?,
    val deviceId: String?,
    val appPackageName: String?,
)

/** Shared JSON config used across the SDK; public so the `:android` bridge can (de)serialize wire messages too. */
val EncatchJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(EncatchJson) }
    install(SSE)
}

/**
 * Thin Ktor-backed HTTP layer mirroring `_post`/`_buildHeaders` from the RN SDK's
 * `encatch.ts`, plus the SSE (`streamQnaWithAi`) and multipart (`uploadFile`) calls.
 */
class EncatchApiClient internal constructor(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val authStateProvider: () -> AuthState,
    private val onUserPendingRetryExhausted: suspend () -> Unit = {},
) {
    private fun buildAuthHeaders(signatureTime: String? = null): Map<String, String> {
        val auth = authStateProvider()
        val apiKey = auth.apiKey ?: throw EncatchNotInitializedException()
        return buildMap {
            put("X-Api-Key", apiKey)
            auth.sessionId?.let { put("X-Session-Id", it) }
            auth.userName?.let { put("X-User-Name", it) }
            auth.userId?.let { put("X-User-Id", it) }
            auth.userSignature?.let { put("X-User-Signature", it) }
            auth.deviceId?.let { put("X-Device-Id", it) }
            signatureTime?.let { put("X-User-Signature-Time", it) }
            auth.appPackageName?.let { put("Referer", it) }
        }
    }

    private fun HttpStatusCode.isSuccess() = value in 200..299

    /** POSTs [body] (already-serialized JSON) to [endpoint], returns the parsed JSON response. */
    suspend fun post(endpoint: String, body: JsonElement, signatureTime: String? = null): JsonObject {
        val url = "${baseUrlProvider()}/$endpoint"
        val authHeaders = buildAuthHeaders(signatureTime)

        val response: HttpResponse = httpClient.post(url) {
            headers { authHeaders.forEach { (k, v) -> append(k, v) } }
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        val responseText = response.bodyAsText()
        val parsed = runCatching { EncatchJson.parseToJsonElement(responseText).jsonObject }.getOrNull()

        if (parsed != null && parsed.toResponseMeta().userPendingRetryExhausted) {
            onUserPendingRetryExhausted()
        }

        if (!response.status.isSuccess() || parsed == null) {
            throw EncatchApiException(endpoint, response.status.value, responseText)
        }

        return parsed
    }

    /** Multipart file upload with progress, mirrors the RN SDK's XHR-based `uploadFile`. */
    suspend fun uploadFile(
        feedbackConfigurationId: String,
        questionId: String,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String?,
        onProgress: ((Int) -> Unit)? = null,
    ): UploadFileResponse {
        val authHeaders = buildAuthHeaders()
        val url = "${baseUrlProvider()}/${Endpoints.UPLOAD}"

        val response = httpClient.post(url) {
            headers { authHeaders.forEach { (k, v) -> append(k, v) } }
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("formId", feedbackConfigurationId)
                        append("questionId", questionId)
                        append(
                            "file",
                            fileBytes,
                            io.ktor.http.Headers.build {
                                append(HttpHeaders.ContentType, mimeType ?: "application/octet-stream")
                                append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                            },
                        )
                    },
                ),
            )
            if (onProgress != null) {
                var lastReported = -1
                onUpload { bytesSentTotal, contentLength ->
                    val total = contentLength ?: return@onUpload
                    if (total <= 0) return@onUpload
                    val pct = min(100, max(0, (bytesSentTotal.toDouble() / total * 100).roundToInt()))
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(pct)
                    }
                }
            }
        }

        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val message = runCatching {
                val obj = EncatchJson.parseToJsonElement(text).jsonObject
                obj["message"]?.jsonPrimitive?.contentOrNull ?: obj["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull() ?: "Upload failed (${response.status.value})"
            throw EncatchApiException(Endpoints.UPLOAD, response.status.value, message)
        }

        return runCatching {
            val obj = EncatchJson.parseToJsonElement(text).jsonObject
            UploadFileResponse(fileUrl = obj["fileUrl"]!!.jsonPrimitive.content)
        }.getOrElse {
            throw EncatchApiException(Endpoints.UPLOAD, response.status.value, "Failed to parse upload response")
        }
    }

    /** SSE stream for Q&A with AI: `event: chunk|done|error` frames, `data:` payload `{delta}`/`{answer}`/`{message}`. */
    suspend fun streamQnaWithAi(
        feedbackConfigurationId: String,
        questionId: String,
        conversation: List<QnaWithAiConversationTurn>,
        onChunk: (String) -> Unit,
        onDone: (String) -> Unit,
    ) {
        val authHeaders = buildAuthHeaders()
        val bodyJson = kotlinx.serialization.json.buildJsonObject {
            put("feedbackConfigurationId", kotlinx.serialization.json.JsonPrimitive(feedbackConfigurationId))
            put("questionId", kotlinx.serialization.json.JsonPrimitive(questionId))
            put(
                "conversation",
                EncatchJson.encodeToJsonElement(
                    kotlinx.serialization.serializer<List<QnaWithAiConversationTurn>>(),
                    conversation,
                ),
            )
        }.toString()

        var accumulatedAnswer = ""
        var doneOrErrorReceived = false
        var streamError: Throwable? = null

        httpClient.sse(
            request = {
                url("${baseUrlProvider()}/${Endpoints.QNA_WITH_AI_STREAM}")
                method = HttpMethod.Post
                authHeaders.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(bodyJson)
            },
        ) {
            incoming.collect { event ->
                val eventName = event.event ?: return@collect
                val dataRaw = event.data?.trim() ?: return@collect
                if (dataRaw.isEmpty()) return@collect

                val payload = runCatching { EncatchJson.parseToJsonElement(dataRaw).jsonObject }.getOrNull() ?: return@collect

                when (eventName) {
                    "chunk" -> {
                        val delta = payload["delta"]?.jsonPrimitive?.contentOrNull ?: ""
                        accumulatedAnswer += delta
                        onChunk(delta)
                    }
                    "done" -> {
                        doneOrErrorReceived = true
                        val answer = payload["answer"]?.jsonPrimitive?.contentOrNull ?: accumulatedAnswer
                        onDone(answer)
                    }
                    "error" -> {
                        doneOrErrorReceived = true
                        val message = payload["message"]?.jsonPrimitive?.contentOrNull ?: "Stream error"
                        streamError = Exception(message)
                    }
                }
            }
        }

        streamError?.let { throw it }
        if (!doneOrErrorReceived) {
            throw Exception("[Encatch] Q&A with AI stream ended without a done/error event")
        }
    }
}
