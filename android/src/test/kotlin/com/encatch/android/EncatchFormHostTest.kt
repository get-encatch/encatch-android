package com.encatch.android

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import android.os.Looper
import com.encatch.core.Encatch
import com.encatch.core.EncatchInternalEmitter
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EncatchFormHostTest {

    private val application: Application get() = ApplicationProvider.getApplicationContext()

    private fun lifecycleCallbackCount(): Int {
        val field = Application::class.java.getDeclaredField("mActivityLifecycleCallbacks")
        field.isAccessible = true
        return (field.get(application) as java.util.ArrayList<*>).size
    }

    private val currentActivityField =
        EncatchFormHost::class.java.getDeclaredField("currentActivity").apply { isAccessible = true }

    private var currentActivity: Activity?
        get() = currentActivityField.get(EncatchFormHost) as Activity?
        set(value) = currentActivityField.set(EncatchFormHost, value)

    // Robolectric gives each test a new Application while the host singleton lives on: start
    // each test with the host not installed, as in a new process.
    @Before
    fun setUp() {
        val installed = EncatchFormHost::class.java.getDeclaredField("installed").apply { isAccessible = true }
        (installed.get(EncatchFormHost) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        currentActivity = null
    }

    @After
    fun tearDown() {
        currentActivity = null
        EncatchInternalEmitter.removeAllListeners()
    }

    @Test
    fun install_isIdempotent() {
        EncatchFormHostInitializer().create(application)
        assertTrue(EncatchFormHost.isInstalled)
        val count = lifecycleCallbackCount()
        EncatchFormHost.install(application)
        EncatchFormHost.install(application)
        EncatchFormHostInitializer().create(application)
        assertEquals(count, lifecycleCallbackCount())
    }

    @Test
    fun hostInstalledBeforeTheScreen_learnsItOnResume() {
        EncatchFormHost.install(application)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertSame(activity, currentActivity)
    }

    @Test
    fun attach_afterTheScreenResumed_usesThatScreen() {
        EncatchFormHost.install(application)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        // as for a host installed after the screen resumed: it missed onActivityResumed
        currentActivity = null
        EncatchFormHost.attach(activity as android.content.Context)
        assertSame(activity, currentActivity)
    }

    @Test
    fun attach_withAPausedScreen_doesNotUseIt() {
        EncatchFormHost.install(application)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller.pause()
        assertNull(currentActivity)
        EncatchFormHost.attach(controller.get())
        assertNull(currentActivity)
    }

    private fun isFormVisible(): Boolean {
        val field = Encatch::class.java.getDeclaredField("isFormVisible").apply { isAccessible = true }
        return field.get(Encatch) as Boolean
    }

    private fun showForm(presentation: String) {
        EncatchInternalEmitter.emit(
            InternalEvent.ShowForm(
                ShowFormPayload(
                    formId = "form-1",
                    formConfig = ShowFormResponse(feedbackConfigurationId = "cfg-1"),
                    resetMode = ResetMode.ALWAYS,
                    triggerType = TriggerType.MANUAL,
                    presentation = presentation,
                ),
            ),
        )
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun modalShow_withNoScreen_isRecordedAsNotOpen() {
        EncatchFormHost.install(application)
        Encatch.setFormVisible(true)
        showForm("modal")
        assertFalse(isFormVisible())
    }

    @Test
    fun inlineShow_withNoScreen_leavesVisibilityAlone() {
        EncatchFormHost.install(application)
        Encatch.setFormVisible(true)
        showForm("inline")
        assertTrue(isFormVisible())
        Encatch.setFormVisible(false)
    }

    @Test
    fun install_offTheMainThread_completesOnMain() {
        val worker = Thread { EncatchFormHost.install(application) }
        worker.start()
        worker.join()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        assertSame(activity, currentActivity)
    }
}
