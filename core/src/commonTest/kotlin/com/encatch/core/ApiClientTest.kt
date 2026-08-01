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
