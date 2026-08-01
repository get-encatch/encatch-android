package com.encatch.core

/**
 * Inline slot registry, mirroring `form-presentation-registry.ts`.
 *
 * Maintains an ordered list of mounted inline slots (e.g. `EncatchInlineFormView`s in the
 * `:android` module). When `showForm` fires, [resolvePresentationTarget] determines whether
 * it should render inline (matching slot found) or fall through to the modal form dialog.
 *
 * Routing rules:
 *  1. Exact match — first slot whose formId matches the payload ids wins.
 *  2. Wildcard    — first slot with no formId catches anything not exact-matched.
 *  3. Modal       — no inline slot registered or none match.
 */
data class InlineSlot(val slotId: String, val formId: String? = null)

sealed class PresentationTarget {
    data class Inline(val slotId: String) : PresentationTarget()
    object Modal : PresentationTarget()
}

object InlineSlotRegistry {
    private val slots = mutableListOf<InlineSlot>()

    /**
     * Registers a new inline slot on view attach. Returns an opaque slotId to use with
     * [unregisterInlineSlot] / [updateInlineSlot]. Registration order is preserved —
     * first-registered wins for wildcard resolution.
     */
    fun registerInlineSlot(formId: String? = null): String {
        val slotId = uuidV7()
        slots.add(InlineSlot(slotId, formId))
        return slotId
    }

    /** Removes an inline slot on view detach. */
    fun unregisterInlineSlot(slotId: String) {
        slots.removeAll { it.slotId == slotId }
    }

    /** Updates the formId of an existing slot without changing its registration order. */
    fun updateInlineSlot(slotId: String, formId: String?) {
        val index = slots.indexOfFirst { it.slotId == slotId }
        if (index != -1) slots[index] = slots[index].copy(formId = formId)
    }

    /**
     * Determines whether the given showForm payload should render inline or modal.
     *
     * ID matching checks the slot's formId against [formId] (the slug/uuid passed by the
     * caller, or formConfigurationId) and [feedbackConfigurationId] (server-resolved id).
     * Single pass: first exact match wins, then the first wildcard; else modal.
     */
    fun resolvePresentationTarget(formId: String, feedbackConfigurationId: String?): PresentationTarget {
        val candidateIds = setOfNotNull(formId.takeIf { it.isNotEmpty() }, feedbackConfigurationId?.takeIf { it.isNotEmpty() })

        var firstWildcard: InlineSlot? = null
        for (slot in slots) {
            if (slot.formId != null) {
                if (slot.formId in candidateIds) return PresentationTarget.Inline(slot.slotId)
            } else if (firstWildcard == null) {
                firstWildcard = slot
            }
        }

        return firstWildcard?.let { PresentationTarget.Inline(it.slotId) } ?: PresentationTarget.Modal
    }

    /** Exposed for testing only — do not use in production code. */
    fun slotsSnapshot(): List<InlineSlot> = slots.toList()

    /** Exposed for testing only — clears the registry. */
    fun clearSlots() {
        slots.clear()
    }
}
