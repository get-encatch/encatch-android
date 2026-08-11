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
 * Compose variant counterpart to [ScreenshotFlowTest] — same init -> show modal -> show inline
 * flow, driven against `ComposeMainActivity` (Compose UI wrapping the SDK's classic-Views UI via
 * `AndroidView` interop). Screenshot-based rather than Compose-semantics-based on purpose: proves
 * what actually renders on screen, matching how every other variant in this suite is verified.
 */
class ComposeScreenshotFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(ComposeMainActivity::class.java)

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val screenshotDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "screenshots-compose").apply { mkdirs() }
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "$name.png"))
    }

    @Test
    fun composeVariant_initShowModalShowInline() {
        screenshot("00-launch")

        device.findObject(By.text("Init SDK")).click()
        device.wait(Until.hasObject(By.textContains("Initialized: true")), 5_000)
        screenshot("01-initialized")

        device.findObject(By.text("Show modal form")).click()
        device.wait(Until.hasObject(By.textContains("Submit")), 8_000)
        screenshot("02-modal-form")

        device.pressBack()
        device.waitForIdle()

        device.findObject(By.text("Show inline form")).click()
        device.wait(Until.hasObject(By.textContains("Submit")), 8_000)
        screenshot("03-inline-form")
    }
}
