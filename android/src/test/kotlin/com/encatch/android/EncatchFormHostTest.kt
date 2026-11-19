package com.encatch.android

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.encatch.core.EncatchInternalEmitter
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
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
}
