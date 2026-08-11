package com.encatch.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/**
 * Schedules `exit_form` completion CTAs after `form:complete`, mirroring `pendingCompletionCta.ts`.
 * The `:android` module calls [schedule]/[cancel]; actual URL opening (Custom Tabs / system
 * browser) is platform UI and lives in `:android`, invoked here via [RedirectOpener].
 */
fun interface RedirectOpener {
    suspend fun openInternal(url: String)
}

class PendingCompletionCtaScheduler(
    private val scope: CoroutineScope,
    private val redirectOpener: RedirectOpener,
    private val emitEvent: (EventType, EventPayload) -> Unit,
    private val openExternal: suspend (String) -> Unit,
) {
    companion object {
        const val REDIRECT_INTERNAL_AFTER_CLOSE_DELAY_MS = 400L
        const val CTA_AFTER_CLOSE_DELAY_MS = 50L
    }

    private val pendingJobs = mutableMapOf<String, Job>()

    private fun effectiveDelayMs(pending: PendingCompletionCta): Long {
        if (pending.autoTriggerDelayMs > 0) return pending.autoTriggerDelayMs
        if (pending.action == CtaAction.REDIRECT_INTERNAL) return REDIRECT_INTERNAL_AFTER_CLOSE_DELAY_MS
        if (pending.action != CtaAction.DISMISS) return CTA_AFTER_CLOSE_DELAY_MS
        return 0
    }

    fun cancel(formId: String? = null) {
        if (formId != null) {
            pendingJobs.remove(formId)?.cancel()
            return
        }
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
    }

    fun schedule(formId: String, pending: PendingCompletionCta) {
        cancel(formId)
        val delayMs = effectiveDelayMs(pending)
        if (delayMs <= 0) {
            scope.launch { execute(formId, pending) }
            return
        }
        pendingJobs[formId] = scope.launch {
            delay(delayMs)
            pendingJobs.remove(formId)
            execute(formId, pending)
        }
    }

    private suspend fun execute(formId: String, pending: PendingCompletionCta) {
        val data: JsonObject = buildJsonObject {
            put("action", pending.action)
            put("surface", pending.surface)
            put("trigger", pending.trigger)
            pending.url?.let { put("url", it) }
            pending.route?.let { put("route", it) }
        }

        when (pending.action) {
            CtaAction.DISMISS -> return
            CtaAction.APP_NAVIGATE -> {
                emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = formId, timestamp = currentTimeMillis(), data = data))
            }
            CtaAction.REDIRECT_INTERNAL -> {
                val url = pending.url ?: return
                redirectOpener.openInternal(url)
                emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = formId, timestamp = currentTimeMillis(), data = data))
            }
            CtaAction.REDIRECT_EXTERNAL -> {
                val url = pending.url ?: return
                openExternal(url)
                emitEvent(EventType.FORM_CTA_TRIGGERED, EventPayload(formId = formId, timestamp = currentTimeMillis(), data = data))
            }
        }
    }
}

/** Defensive parse of the wire-format `pendingCompletionCta` payload, mirrors `parsePendingCompletionCta`. */
fun parsePendingCompletionCta(json: JsonObject?): PendingCompletionCta? {
    if (json == null) return null
    val action = (json["action"] as? JsonPrimitive)?.contentOrNull ?: return null
    val delayRaw = (json["autoTriggerDelayMs"] as? JsonPrimitive)?.doubleOrNull
    val autoTriggerDelayMs = if (delayRaw != null && delayRaw >= 0) delayRaw.toLong() else 0L
    val surface = (json["surface"] as? JsonPrimitive)?.contentOrNull
    return PendingCompletionCta(
        action = action,
        url = (json["url"] as? JsonPrimitive)?.contentOrNull,
        route = (json["route"] as? JsonPrimitive)?.contentOrNull,
        surface = if (surface == "link") "link" else "inApp",
        trigger = "auto",
        autoTriggerDelayMs = autoTriggerDelayMs,
    )
}
