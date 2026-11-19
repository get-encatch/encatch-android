package com.encatch.core

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.concurrent.Volatile

/**
 * A fake Encatch API for tests that drive the [Encatch] singleton: records every request (endpoint,
 * headers, body, time) and answers with [reply]. Plug in with [install]; the SDK runs on
 * Dispatchers.Default, so waits are in real time ([eventually], [settle]).
 */
internal class MockApi {
    data class Call(val endpoint: String, val headers: Headers, val body: JsonObject, val atMs: Long) {
        fun header(name: String): String? = headers[name]
        fun str(field: String): String? = body[field]?.jsonPrimitive?.content
    }

    data class Reply(
        val status: HttpStatusCode = HttpStatusCode.OK,
        val body: String = "{}",
        val headers: Map<String, String> = emptyMap(),
        val delayMs: Long = 0,
    )

    val calls = MutableStateFlow<List<Call>>(emptyList())
    @Volatile var reply: (endpoint: String, body: JsonObject) -> Reply = { endpoint, _ ->
        if (endpoint == "identify-user") Reply(body = """{"userId":"user-1"}""") else Reply()
    }

    val settings = InMemorySettings()

    fun called(endpoint: String): List<Call> = calls.value.filter { it.endpoint == endpoint }

    fun install() {
        calls.value = emptyList()
        Encatch.settingsFactory = { settings }
        Encatch.engineFactory = {
            MockEngine { request ->
                val endpoint = request.url.encodedPath.substringAfterLast('/')
                val text = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString() ?: "{}"
                val body = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
                calls.update { it + Call(endpoint, request.headers, body, currentTimeMillis()) }
                val r = reply(endpoint, body)
                if (r.delayMs > 0) delay(r.delayMs)
                val headers = headersOf(
                    *(r.headers + (HttpHeaders.ContentType to "application/json")).map { it.key to listOf(it.value) }.toTypedArray(),
                )
                respond(r.body, r.status, headers)
            }
        }
    }

    suspend fun uninstall() {
        Encatch.clearAll()
        Encatch.engineFactory = null
        Encatch.settingsFactory = null
    }
}

/** Waits, in real time (the SDK's own scope runs on Dispatchers.Default), until [done] holds. */
internal suspend fun eventually(timeoutMs: Long = 5_000, done: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(timeoutMs) { while (!done()) delay(20) }
}

internal suspend fun settle(ms: Long = 400) = withContext(Dispatchers.Default) { delay(ms) }
