package com.encatch.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.onUpload
import io.ktor.client.plugins.timeout
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

/** JSON POST timeouts (ms). Uploads and the SSE stream override per-request below. */
internal const val DEFAULT_TIMEOUT_MS = 30_000L

/**
 * Upload idle timeout (ms). Uploads get NO total-time limit — a 50 MB video on a slow uplink
 * can legitimately take many minutes, so any fixed request timeout eventually kills a valid
 * upload (a 120s cap was observed killing a 14 MB video mid-transfer). Instead only the
 * socket (idle) timeout applies: the upload fails when no data moves for this long, matching
 * the RN (XHR), Flutter (http), and Swift (URLSession inactivity timer) SDKs' semantics.
 */
internal const val UPLOAD_IDLE_TIMEOUT_MS = 120_000L

internal fun createHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    install(ContentNegotiation) { json(EncatchJson) }
    install(SSE)
    install(HttpTimeout) {
        connectTimeoutMillis = DEFAULT_TIMEOUT_MS
        requestTimeoutMillis = DEFAULT_TIMEOUT_MS
        socketTimeoutMillis = DEFAULT_TIMEOUT_MS
    }
}

/** Human-readable byte size for network-log summaries of binary payloads. */
internal fun formatByteSize(bytes: Int): String = when {
    bytes >= 1_048_576 -> "${(bytes * 10L / 1_048_576).toDouble() / 10.0} MB"
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

/** Flattens Ktor response headers into a plain map for [EncatchNetworkLogEntry] (multi-value headers joined with ", "). */
internal fun io.ktor.http.Headers.flattenForLog(): Map<String, String> =
    entries().associate { (name, values) -> name to values.joinToString(", ") }

/**
 * One completed SDK HTTP call (request + response), emitted to `Encatch.onNetworkLog` for
 * host-app debugging tools. Covers all JSON POST endpoints and the multipart upload (whose
 * binary body is logged as a `<multipart>` summary line, not the payload); only the
 * Q&A-with-AI SSE stream is not logged (streaming). The API key header is masked to its last
 * 5 characters.
 */
data class EncatchNetworkLogEntry(
    val timestampMs: Long,
    val method: String,
    val endpoint: String,
    val url: String,
    val requestHeaders: Map<String, String>,
    val requestBody: String,
    val status: Int,
    val responseBody: String,
    val durationMs: Long,
    val error: String?,
    /** Response headers (e.g. `x-encatch-id` for correlating with server-side logs); empty when the request never got a response. */
    val responseHeaders: Map<String, String> = emptyMap(),
)

/**
 * Thin Ktor-backed HTTP layer mirroring `_post`/`_buildHeaders` from the RN SDK's
 * `encatch.ts`, plus the SSE (`streamQnaWithAi`) and multipart (`uploadFile`) calls.
 */
class EncatchApiClient internal constructor(
    private val httpClient: HttpClient,
    private val baseUrlProvider: () -> String,
    private val authStateProvider: () -> AuthState,
    private val onUserPendingRetryExhausted: suspend () -> Unit = {},
    private val logger: EncatchLogger = DefaultEncatchLogger { false },
    private val networkLogSink: (EncatchNetworkLogEntry) -> Unit = {},
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

        logger.debug("POST $endpoint -> $url")
        logger.debug("Request headers:\n${redactedHeadersForLog(authHeaders)}")
        logger.debug("Request body:\n$body")

        val startedAt = currentTimeMillis()
        // Even in debugMode, never expose the full API key — keep just the last 5 characters
        // so entries can be matched to a key without being usable as one.
        val logHeaders = authHeaders.mapValues { (k, v) ->
            if (k == "X-Api-Key") "•••${v.takeLast(5)}" else v
        } + ("Content-Type" to "application/json")
        fun emitLog(status: Int, responseBody: String, error: String?, responseHeaders: Map<String, String> = emptyMap()) {
            networkLogSink(
                EncatchNetworkLogEntry(
                    timestampMs = startedAt,
                    method = "POST",
                    endpoint = endpoint,
                    url = url,
                    requestHeaders = logHeaders,
                    requestBody = body.toString(),
                    status = status,
                    responseBody = responseBody,
                    durationMs = currentTimeMillis() - startedAt,
                    error = error,
                    responseHeaders = responseHeaders,
                ),
            )
        }

        val response: HttpResponse = try {
            httpClient.post(url) {
                headers { authHeaders.forEach { (k, v) -> append(k, v) } }
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (err: Throwable) {
            emitLog(status = 0, responseBody = "", error = err.message ?: err.toString())
            throw err
        }

        val responseText = response.bodyAsText()
        val parsed = runCatching { EncatchJson.parseToJsonElement(responseText).jsonObject }.getOrNull()
        emitLog(status = response.status.value, responseBody = responseText, error = null, responseHeaders = response.headers.flattenForLog())

        logger.debug("POST $endpoint <- ${response.status.value}")
        logger.debug("Response body:\n${parsed ?: responseText}")

        if (parsed != null && parsed.toResponseMeta().userPendingRetryExhausted) {
            onUserPendingRetryExhausted()
        }

        if (!response.status.isSuccess() || parsed == null) {
            val error = EncatchApiException(endpoint, response.status.value, responseText)
            logger.warn(error.message ?: "[Encatch API] $endpoint failed")
            throw error
        }

        return parsed
    }

    private fun redactedHeadersForLog(headers: Map<String, String>): String =
        headers.entries.joinToString("\n") { (k, v) -> "$k: ${if (k == "X-Api-Key") "***" else v}" }

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

        // Log like post() does, but summarize the binary body instead of dumping it.
        val startedAt = currentTimeMillis()
        val logHeaders = authHeaders.mapValues { (k, v) ->
            if (k == "X-Api-Key") "•••${v.takeLast(5)}" else v
        } + ("Content-Type" to "multipart/form-data")
        val logBody = "<multipart> file=$fileName, ${formatByteSize(fileBytes.size)}, " +
            "${mimeType ?: "application/octet-stream"}; formId=$feedbackConfigurationId, questionId=$questionId"
        fun emitLog(status: Int, responseBody: String, error: String?, responseHeaders: Map<String, String> = emptyMap()) {
            networkLogSink(
                EncatchNetworkLogEntry(
                    timestampMs = startedAt,
                    method = "POST",
                    endpoint = Endpoints.UPLOAD,
                    url = url,
                    requestHeaders = logHeaders,
                    requestBody = logBody,
                    status = status,
                    responseBody = responseBody,
                    durationMs = currentTimeMillis() - startedAt,
                    error = error,
                    responseHeaders = responseHeaders,
                ),
            )
        }

        val response = try {
            uploadFileRequest(url, authHeaders, feedbackConfigurationId, questionId, fileBytes, fileName, mimeType, onProgress)
        } catch (err: Throwable) {
            emitLog(status = 0, responseBody = "", error = err.message ?: err.toString())
            throw err
        }

        val text = response.bodyAsText()
        emitLog(status = response.status.value, responseBody = text, error = null, responseHeaders = response.headers.flattenForLog())
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

    private suspend fun uploadFileRequest(
        url: String,
        authHeaders: Map<String, String>,
        feedbackConfigurationId: String,
        questionId: String,
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String?,
        onProgress: ((Int) -> Unit)?,
    ): HttpResponse {
        return httpClient.post(url) {
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = UPLOAD_IDLE_TIMEOUT_MS
            }
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
                // A streamed AI answer can legitimately run (and idle between chunks) far past
                // the JSON-post defaults — exempt the stream from the request timeout entirely.
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
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
