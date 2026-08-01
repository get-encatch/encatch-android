package com.encatch.core

import kotlinx.serialization.json.JsonElement

/** Minimal typed pub/sub, mirroring `emitter.ts`'s `TypedEmitter`. No external dependency. */
open class Emitter<T> {
    private val listeners = mutableListOf<(T) -> Unit>()

    fun on(listener: (T) -> Unit): () -> Unit {
        listeners.add(listener)
        return { off(listener) }
    }

    fun off(listener: (T) -> Unit) {
        listeners.remove(listener)
    }

    fun emit(payload: T) {
        // Iterate over a snapshot so listeners can safely unsubscribe during emit.
        listeners.toList().forEach { listener ->
            runCatching { listener(payload) }
        }
    }

    fun removeAllListeners() {
        listeners.clear()
    }
}

/** Internal event bridging [Encatch] to the `:android` module's WebView/inline form UI. */
sealed class InternalEvent {
    data class ShowForm(val payload: ShowFormPayload) : InternalEvent()
    data class DismissForm(val formConfigurationId: String? = null) : InternalEvent()
    data class SendToWebView(val message: SDKMessage) : InternalEvent()
    data class UserIdentified(val userName: String?, val userId: String?) : InternalEvent()
}

data class ShowFormPayload(
    val formId: String,
    val formConfig: ShowFormResponse,
    val resetMode: ResetMode,
    val triggerType: TriggerType,
    val prefillResponses: Map<String, JsonElement> = emptyMap(),
    val locale: String? = null,
    val theme: Theme? = null,
    val context: Map<String, JsonElement>? = null,
    /** Resolved presentation target: "inline" renders in an inline slot, "modal" in the overlay dialog. */
    val presentation: String = "modal",
    val inlineSlotId: String? = null,
)

/** Shared internal emitter singleton, mirrors the RN SDK's exported `_internalEmitter`. */
object EncatchInternalEmitter : Emitter<InternalEvent>()
