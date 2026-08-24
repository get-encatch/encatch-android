package com.encatch.android

import android.app.Activity
import android.os.Looper
import android.widget.FrameLayout
import com.encatch.core.EncatchInternalEmitter
import com.encatch.core.InlineSlotRegistry
import com.encatch.core.InternalEvent
import com.encatch.core.ResetMode
import com.encatch.core.ShowFormPayload
import com.encatch.core.ShowFormResponse
import com.encatch.core.TriggerType
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EncatchInlineFormViewTest {

    private lateinit var activityController: ActivityController<Activity>
    private lateinit var activity: Activity
    private lateinit var root: FrameLayout
    private lateinit var view: EncatchInlineFormView

    @Before
    fun setUp() {
        InlineSlotRegistry.clearSlots()
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        activity = activityController.get()
        root = FrameLayout(activity)
        activity.setContentView(root)
        view = EncatchInlineFormView(activity)
    }

    @After
    fun tearDown() {
        InlineSlotRegistry.clearSlots()
        EncatchInternalEmitter.removeAllListeners()
    }

    @Test
    fun attach_registersInlineSlot() {
        assertTrue(InlineSlotRegistry.slotsSnapshot().isEmpty())
        root.addView(view)
        assertEquals(1, InlineSlotRegistry.slotsSnapshot().size)
    }

    @Test
    fun detach_unregistersInlineSlot() {
        root.addView(view)
        assertEquals(1, InlineSlotRegistry.slotsSnapshot().size)
        root.removeView(view)
        assertTrue(InlineSlotRegistry.slotsSnapshot().isEmpty())
    }

    @Test
    fun attach_registersWithConfiguredFormId() {
        view.formId = "my-form"
        root.addView(view)
        assertEquals("my-form", InlineSlotRegistry.slotsSnapshot().single().formId)
    }

    @Test
    fun changingFormIdAfterAttach_updatesRegisteredSlot() {
        root.addView(view)
        view.formId = "changed-form"
        assertEquals("changed-form", InlineSlotRegistry.slotsSnapshot().single().formId)
    }

    @Test
    fun showFormEvent_matchingSlot_loadsWebViewAndSetsSkeletonHeight() {
        root.addView(view)
        val slotId = InlineSlotRegistry.slotsSnapshot().single().slotId

        val payload = showFormPayload(presentation = "inline", inlineSlotId = slotId)
        emitAndIdle(InternalEvent.ShowForm(payload))

        assertNotNull(view.getChildAt(0))
        assertTrue((view.layoutParams?.height ?: 0) > 0)
    }

    @Test
    fun showFormEvent_nonMatchingSlot_isIgnored() {
        root.addView(view)

        val payload = showFormPayload(presentation = "inline", inlineSlotId = "some-other-slot-id")
        emitAndIdle(InternalEvent.ShowForm(payload))

        assertEquals(0, view.childCount)
    }

    @Test
    fun showFormEvent_modalPresentation_isIgnoredByInlineView() {
        root.addView(view)

        val payload = showFormPayload(presentation = "modal", inlineSlotId = null)
        emitAndIdle(InternalEvent.ShowForm(payload))

        assertEquals(0, view.childCount)
    }

    @Test
    fun dismissFormEvent_clearsActiveForm() {
        root.addView(view)
        val slotId = InlineSlotRegistry.slotsSnapshot().single().slotId
        emitAndIdle(InternalEvent.ShowForm(showFormPayload("inline", slotId)))
        assertTrue(view.childCount > 0)

        emitAndIdle(InternalEvent.DismissForm())

        assertEquals(0, view.childCount)
        assertEquals(0, view.layoutParams?.height)
    }

    @Test
    fun anotherPresenterTakingOver_clearsThisSlotsActiveForm() {
        root.addView(view)
        val slotId = InlineSlotRegistry.slotsSnapshot().single().slotId
        emitAndIdle(InternalEvent.ShowForm(showFormPayload("inline", slotId)))
        assertTrue(view.childCount > 0)

        // A different form takes over (e.g. modal, or a different inline slot).
        emitAndIdle(InternalEvent.ShowForm(showFormPayload("modal", null)))

        assertEquals(0, view.childCount)
    }

    /**
     * Regression test for automatic triggers: core emits ShowForm from its Dispatchers.Default
     * scope, so the event arrives on a background thread. The view must marshal to the main
     * thread and still load the form (before the fix, the view ops threw off-main and the form
     * silently never appeared).
     */
    @Test
    fun showFormEvent_emittedFromBackgroundThread_isMarshaledToMainThread() {
        root.addView(view)
        val slotId = InlineSlotRegistry.slotsSnapshot().single().slotId
        val payload = showFormPayload(presentation = "inline", inlineSlotId = slotId)

        val background = Thread { EncatchInternalEmitter.emit(InternalEvent.ShowForm(payload)) }
        background.start()
        background.join()
        shadowOf(Looper.getMainLooper()).idle()

        assertNotNull(view.getChildAt(0))
        assertTrue((view.layoutParams?.height ?: 0) > 0)
    }

    /** Emits on the calling thread, then drains the main looper the view's handler posts to. */
    private fun emitAndIdle(event: InternalEvent) {
        EncatchInternalEmitter.emit(event)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun showFormPayload(presentation: String, inlineSlotId: String?) = ShowFormPayload(
        formId = "form-1",
        formConfig = ShowFormResponse(feedbackConfigurationId = "cfg-1"),
        resetMode = ResetMode.ALWAYS,
        triggerType = TriggerType.MANUAL,
        presentation = presentation,
        inlineSlotId = inlineSlotId,
    )
}
