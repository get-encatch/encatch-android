package com.encatch.core

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class InlineSlotRegistryTest {

    @BeforeTest
    fun setUp() {
        InlineSlotRegistry.clearSlots()
    }

    @AfterTest
    fun tearDown() {
        InlineSlotRegistry.clearSlots()
    }

    @Test
    fun resolve_noSlots_returnsModal() {
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertIs<PresentationTarget.Modal>(target)
    }

    @Test
    fun resolve_exactMatchOnFormId_returnsInline() {
        val slotId = InlineSlotRegistry.registerInlineSlot("form-1")
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertEquals(PresentationTarget.Inline(slotId), target)
    }

    @Test
    fun resolve_exactMatchOnFeedbackConfigurationId_returnsInline() {
        val slotId = InlineSlotRegistry.registerInlineSlot("cfg-1")
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertEquals(PresentationTarget.Inline(slotId), target)
    }

    @Test
    fun resolve_wildcardSlot_catchesUnmatchedForm() {
        val wildcardSlotId = InlineSlotRegistry.registerInlineSlot(null)
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertEquals(PresentationTarget.Inline(wildcardSlotId), target)
    }

    @Test
    fun resolve_exactMatchWinsOverWildcard() {
        InlineSlotRegistry.registerInlineSlot(null)
        val exactSlotId = InlineSlotRegistry.registerInlineSlot("form-1")
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertEquals(PresentationTarget.Inline(exactSlotId), target)
    }

    @Test
    fun resolve_nonMatchingExactSlot_fallsBackToWildcard() {
        InlineSlotRegistry.registerInlineSlot("some-other-form")
        val wildcardSlotId = InlineSlotRegistry.registerInlineSlot(null)
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertEquals(PresentationTarget.Inline(wildcardSlotId), target)
    }

    @Test
    fun resolve_noMatchAndNoWildcard_returnsModal() {
        InlineSlotRegistry.registerInlineSlot("some-other-form")
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertIs<PresentationTarget.Modal>(target)
    }

    @Test
    fun unregister_removesSlotFromResolution() {
        val slotId = InlineSlotRegistry.registerInlineSlot("form-1")
        InlineSlotRegistry.unregisterInlineSlot(slotId)
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", "cfg-1")
        assertIs<PresentationTarget.Modal>(target)
    }

    @Test
    fun updateInlineSlot_changesFormIdWithoutReordering() {
        val first = InlineSlotRegistry.registerInlineSlot("form-a")
        val second = InlineSlotRegistry.registerInlineSlot("form-b")

        InlineSlotRegistry.updateInlineSlot(first, "form-b")

        // First-registered still wins for the now-shared formId.
        val target = InlineSlotRegistry.resolvePresentationTarget("form-b", null)
        assertEquals(PresentationTarget.Inline(first), target)
        assertEquals(2, InlineSlotRegistry.slotsSnapshot().size)
        assertEquals("form-b", InlineSlotRegistry.slotsSnapshot()[0].formId)
        assertEquals(second, InlineSlotRegistry.slotsSnapshot()[1].slotId)
    }

    @Test
    fun registrationOrder_firstWildcardWins() {
        val firstWildcard = InlineSlotRegistry.registerInlineSlot(null)
        InlineSlotRegistry.registerInlineSlot(null)
        val target = InlineSlotRegistry.resolvePresentationTarget("form-1", null)
        assertEquals(PresentationTarget.Inline(firstWildcard), target)
    }
}
