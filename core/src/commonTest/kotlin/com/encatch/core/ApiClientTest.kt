package com.encatch.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiClientTest {

    private fun client(
        onUserPendingRetryExhausted: suspend () -> Unit = {},
        handler: MockRequestHandler,
    ): EncatchApiClient {
        val engine = MockEngine { request -> handler(request) }
        val httpClient = createHttpClient(engine)
        return EncatchApiClient(
            httpClient = httpClient,
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = {
                AuthState(
                    apiKey = "test-api-key",
                    sessionId = "session-1",
                    userName = "alice",
                    userId = "user-1",
                    userSignature = null,
                    deviceId = "device-1",
                    appPackageName = "com.example.app",
                )
            },
            onUserPendingRetryExhausted = onUserPendingRetryExhausted,
        )
    }

    @Test
    fun post_sendsExpectedAuthHeadersAndEndpoint() = runTest {
        var capturedUrl: String? = null
        var capturedHeaders: io.ktor.http.Headers? = null

        val api = client { request ->
            capturedUrl = request.url.toString()
            capturedHeaders = request.headers
            respond(
                content = """{"message":"ok"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        api.post(Endpoints.TRACK_EVENT, buildJsonObject { put("eventName", JsonPrimitive("app_open")) })

        assertEquals("https://api.encatch.com/${Endpoints.TRACK_EVENT}", capturedUrl)
        assertEquals("test-api-key", capturedHeaders?.get("X-Api-Key"))
        assertEquals("session-1", capturedHeaders?.get("X-Session-Id"))
        assertEquals("alice", capturedHeaders?.get("X-User-Name"))
        assertEquals("user-1", capturedHeaders?.get("X-User-Id"))
        assertEquals("device-1", capturedHeaders?.get("X-Device-Id"))
        assertEquals("com.example.app", capturedHeaders?.get("Referer"))
    }

    @Test
    fun post_parsesPingAgainInAndFeedbackTransactions() = runTest {
        val api = client { _ ->
            respond(
                content = """{"message":"ok","pingAgainIn":45,"pingOnNextPageVisit":false,"${'$'}feedbackTransactions":"ft-xyz"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val res = api.post(Endpoints.PING, buildJsonObject { })
        val meta = res.toResponseMeta()

        assertEquals(45.0, meta.pingAgainIn)
        assertEquals(false, meta.pingOnNextPageVisit)
        assertEquals("ft-xyz", meta.feedbackTransactions)
    }

    @Test
    fun post_4xx_throwsEncatchApiExceptionWithStatus() = runTest {
        val api = client { _ ->
            respond(
                content = """{"message":"bad request"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<EncatchApiException> {
            api.post(Endpoints.TRACK_EVENT, buildJsonObject { })
        }
        assertEquals(400, error.status)
        assertEquals(Endpoints.TRACK_EVENT, error.endpoint)
    }

    @Test
    fun post_5xx_throwsEncatchApiExceptionWithStatus() = runTest {
        val api = client { _ ->
            respond(
                content = """{"message":"server error"}""",
                status = HttpStatusCode.InternalServerError,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val error = assertFailsWith<EncatchApiException> {
            api.post(Endpoints.TRACK_EVENT, buildJsonObject { })
        }
        assertEquals(500, error.status)
    }

    @Test
    fun post_userPendingRetryExhausted_triggersCallback() = runTest {
        var called = false
        val api = client(onUserPendingRetryExhausted = { called = true }) { _ ->
            respond(
                content = """{"user_pending_retry_exhausted":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        api.post(Endpoints.IDENTIFY_USER, buildJsonObject { })

        assertTrue(called)
    }

    @Test
    fun post_userPendingRetryExhaustedInCamelCase_triggersCallback() = runTest {
        var called = false
        val api = client(onUserPendingRetryExhausted = { called = true }) { _ ->
            respond(
                content = """{"pingAgainIn":900,"userPendingRetryExhausted":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        api.post(Endpoints.PING, buildJsonObject { })

        assertTrue(called)
    }

    @Test
    fun post_nameWithoutId_goesAsAVisitor() = runTest {
        var headers: io.ktor.http.Headers? = null
        val engine = MockEngine { request ->
            headers = request.headers
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState("k", null, "alice", null, "sig", null, null, "123") },
        )
        api.post(Endpoints.TRACK_EVENT, buildJsonObject { })
        assertEquals(null, headers?.get("X-User-Name"))
        assertEquals(null, headers?.get("X-User-Id"))
        assertEquals(null, headers?.get("X-User-Signature"), "never on a visitor's request")
    }

    @Test
    fun every401IsReportedWithWhatTheRequestCarriedAndTheReason() = runTest {
        var body = """{"message":"unauthorized"}"""
        val engine = MockEngine { respond(body, HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }
        val replies = mutableListOf<UnauthorizedReply>()
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState("k", null, "alice", "user-1", "sig", null, null, "123") },
            onUnauthorized = { replies += it },
        )
        assertFailsWith<EncatchApiException> { api.post(Endpoints.TRACK_EVENT, buildJsonObject { }) }
        assertEquals(UnauthorizedReply("alice", "sig", true, UnauthorizedReply.Identify.NONE, false), replies.last())
        body = """{"error":"invalid api key","reason":"user_signature_expired"}"""
        assertFailsWith<EncatchApiException> {
            api.send(Endpoints.IDENTIFY_USER, buildJsonObject { }, IdentifyAuth("bob", "sig2", "456"))
        }
        assertEquals(UnauthorizedReply("bob", "sig2", true, UnauthorizedReply.Identify.OWN, true), replies.last())
        assertFailsWith<EncatchApiException> {
            api.send(Endpoints.IDENTIFY_USER, buildJsonObject { }, IdentifyAuth("alice", "sig", null), storedSignature = true)
        }
        assertEquals(UnauthorizedReply("alice", "sig", false, UnauthorizedReply.Identify.STORED_SIGNATURE, true), replies.last())
        // the reason only counts as a string; a body that isn't JSON has none
        for (other in listOf("""{"reason":42}""", """{"reason":null}""", "not json", """{"error":{"reason":"user_signature_expired"}}""")) {
            body = other
            assertFailsWith<EncatchApiException> { api.post(Endpoints.TRACK_EVENT, buildJsonObject { }) }
            assertFalse(replies.last().signatureExpiredReason, other)
        }
        assertEquals(7, replies.size)
    }

    @Test
    fun debugLog_masksTheSignatureInHeadersAndBody() = runTest {
        val lines = mutableListOf<String>()
        val logger = object : EncatchLogger {
            override fun debug(message: String) { lines.add(message) }
            override fun warn(message: String) { lines.add(message) }
        }
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState("secret-api-key", null, "alice", "user-1", "sig-stored", null, null, "123") },
            logger = logger,
        )
        api.send(
            Endpoints.IDENTIFY_USER,
            buildJsonObject { put("userName", JsonPrimitive("alice")); put("userSignature", JsonPrimitive("sig-call")) },
            IdentifyAuth("alice", "sig-call", "123"),
        )
        api.post(Endpoints.TRACK_EVENT, buildJsonObject { })
        val all = lines.joinToString("\n")
        assertTrue(!all.contains("sig-call") && !all.contains("sig-stored") && !all.contains("secret-api-key"), all)
        assertTrue(all.contains("X-User-Signature: ***"))
    }

    @Test
    fun uploadFile_emitsNetworkLogWithMultipartSummary() = runTest {
        val logs = mutableListOf<EncatchNetworkLogEntry>()
        val engine = MockEngine {
            respond(
                content = """{"fileUrl":"https://cdn.encatch.com/u/1.jpg"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState("test-api-key", "session-1", "alice", "user-1", null, "device-1", "com.example.app") },
            networkLogSink = { logs.add(it) },
        )

        api.uploadFile("cfg-1", "q-1", ByteArray(2048), "signature.jpg", "image/jpeg")

        val entry = logs.single()
        assertEquals(Endpoints.UPLOAD, entry.endpoint)
        assertEquals(200, entry.status)
        assertEquals("""{"fileUrl":"https://cdn.encatch.com/u/1.jpg"}""", entry.responseBody)
        // Binary body is summarized, never dumped.
        assertEquals("<multipart> file=signature.jpg, 2 KB, image/jpeg; formId=cfg-1, questionId=q-1", entry.requestBody)
        assertEquals("•••i-key", entry.requestHeaders["X-Api-Key"])
    }

    @Test
    fun uploadFile_failure_emitsNetworkLogWithError() = runTest {
        val logs = mutableListOf<EncatchNetworkLogEntry>()
        val engine = MockEngine {
            respond(
                content = """{"message":"file too large"}""",
                status = HttpStatusCode.PayloadTooLarge,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState("test-api-key", null, null, null, null, null, null) },
            networkLogSink = { logs.add(it) },
        )

        assertFailsWith<EncatchApiException> {
            api.uploadFile("cfg-1", "q-1", ByteArray(10), "big.jpg", "image/jpeg")
        }
        val entry = logs.single()
        assertEquals(413, entry.status)
        assertEquals("""{"message":"file too large"}""", entry.responseBody)
    }

    @Test
    fun post_withoutApiKey_throwsNotInitialized() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val api = EncatchApiClient(
            httpClient = createHttpClient(engine),
            baseUrlProvider = { "https://api.encatch.com" },
            authStateProvider = { AuthState(null, null, null, null, null, null, null) },
        )

        assertFailsWith<EncatchNotInitializedException> {
            api.post(Endpoints.PING, buildJsonObject { })
        }
    }
}
