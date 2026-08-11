package com.encatch.sampleapp

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Drives MainActivity through init -> show modal form -> show inline form, capturing a real
 * device screenshot at each step. Requires `:mock-server` running and reachable at
 * `BuildConfig.MOCK_SERVER_BASE_URL` (set via `-PmockServerBaseUrl=http://10.0.2.2:<port>`,
 * see `sample-app/build.gradle.kts`) — otherwise `initButton` will hit the real backend with a
 * placeholder API key and every subsequent step will silently no-op.
 *
 * Screenshots land in this app's external files dir
 * (`/sdcard/Android/data/com.encatch.sampleapp/files/screenshots/`), pulled by the orchestrator
 * script via `adb pull` after the run.
 */
class ScreenshotFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val screenshotDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "$name.png"))
    }

    @Test
    fun androidViewsVariant_initShowModalShowInline() {
        screenshot("00-launch")

        device.findObject(By.res("com.encatch.sampleapp:id/initButton")).click()
        device.wait(Until.hasObject(By.textContains("Initialized: true")), 5_000)
        screenshot("01-initialized")

        device.findObject(By.res("com.encatch.sampleapp:id/showFormButton")).click()
        device.wait(Until.hasObject(By.textContains("Submit")), 8_000)
        screenshot("02-modal-form")

        device.pressBack()
        device.waitForIdle()

        device.findObject(By.res("com.encatch.sampleapp:id/showInlineFormButton")).click()
        device.wait(Until.hasObject(By.textContains("Submit")), 8_000)
        screenshot("03-inline-form")
    }
}
