package com.encatch.core

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingCompletionCtaParseTest {

    @Test
    fun parse_returnsNull_forMissingAction() {
        val json = Json.parseToJsonElement("""{"surface":"inApp"}""").jsonObject
        assertNull(parsePendingCompletionCta(json))
    }

    @Test
    fun parse_defaultsSurfaceToInApp_unlessExactlyLink() {
        val json1 = Json.parseToJsonElement("""{"action":"app_navigate"}""").jsonObject
        assertEquals("inApp", parsePendingCompletionCta(json1)!!.surface)

        val json2 = Json.parseToJsonElement("""{"action":"redirect_internal","surface":"link"}""").jsonObject
        assertEquals("link", parsePendingCompletionCta(json2)!!.surface)
    }

    @Test
    fun parse_negativeOrMissingDelay_defaultsToZero() {
        val json = Json.parseToJsonElement("""{"action":"dismiss","autoTriggerDelayMs":-5}""").jsonObject
        assertEquals(0L, parsePendingCompletionCta(json)!!.autoTriggerDelayMs)
    }

    @Test
    fun parse_nullInput_returnsNull() {
        assertNull(parsePendingCompletionCta(null as JsonObject?))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PendingCompletionCtaSchedulerTest {

    @Test
    fun dismiss_neverEmitsEvent() = runTest {
        var emitted = false
        val scheduler = PendingCompletionCtaScheduler(
            scope = this,
            redirectOpener = RedirectOpener { },
            emitEvent = { _, _ -> emitted = true },
            openExternal = { },
        )

        scheduler.schedule("form-1", PendingCompletionCta(action = CtaAction.DISMISS, surface = "inApp", trigger = "auto", autoTriggerDelayMs = 0))
        advanceTimeBy(1000)

        assertEquals(false, emitted)
    }

    @Test
    fun appNavigate_emitsImmediatelyWhenNoDelay() = runTest {
        var emittedFormId: String? = null
        val scheduler = PendingCompletionCtaScheduler(
            scope = this,
            redirectOpener = RedirectOpener { },
            emitEvent = { _, payload -> emittedFormId = payload.formId },
            openExternal = { },
        )

        scheduler.schedule("form-2", PendingCompletionCta(action = CtaAction.APP_NAVIGATE, surface = "inApp", trigger = "auto", autoTriggerDelayMs = 0))
        advanceUntilIdle()

        assertEquals("form-2", emittedFormId)
    }

    @Test
    fun redirectInternal_usesDefaultDelayAndOpensUrl() = runTest {
        var openedUrl: String? = null
        var emitted = false
        val scheduler = PendingCompletionCtaScheduler(
            scope = this,
            redirectOpener = RedirectOpener { url -> openedUrl = url },
            emitEvent = { _, _ -> emitted = true },
            openExternal = { },
        )

        scheduler.schedule(
            "form-3",
            PendingCompletionCta(action = CtaAction.REDIRECT_INTERNAL, url = "https://example.com", surface = "inApp", trigger = "auto", autoTriggerDelayMs = 0),
        )

        // Before the default 400ms delay, nothing should have fired yet.
        advanceTimeBy(100)
        assertEquals(null, openedUrl)

        advanceTimeBy(400)
        assertEquals("https://example.com", openedUrl)
        assertEquals(true, emitted)
    }

    @Test
    fun cancel_preventsScheduledExecution() = runTest {
        var emitted = false
        val scheduler = PendingCompletionCtaScheduler(
            scope = this,
            redirectOpener = RedirectOpener { },
            emitEvent = { _, _ -> emitted = true },
            openExternal = { },
        )

        scheduler.schedule("form-4", PendingCompletionCta(action = CtaAction.REDIRECT_EXTERNAL, url = "https://example.com", surface = "inApp", trigger = "auto", autoTriggerDelayMs = 1000))
        scheduler.cancel("form-4")
        advanceTimeBy(2000)

        assertEquals(false, emitted)
    }
}
