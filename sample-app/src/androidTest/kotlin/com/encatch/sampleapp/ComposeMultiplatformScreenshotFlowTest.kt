package com.encatch.sampleapp

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.encatch.composesample.ComposeMultiplatformSampleActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Compose Multiplatform variant counterpart to [ScreenshotFlowTest] — same init -> show modal ->
 * show inline flow, driven against `:compose-sample`'s `ComposeMultiplatformSampleActivity`
 * (Compose Multiplatform commonMain UI wrapping the SDK's classic-Views UI via `AndroidView`
 * interop, same underlying components as the single-platform Android Compose variant).
 */
class ComposeMultiplatformScreenshotFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule<ComposeMultiplatformSampleActivity>(
        Intent(
            ApplicationProvider.getApplicationContext(),
            ComposeMultiplatformSampleActivity::class.java,
        ).putExtra(
            ComposeMultiplatformSampleActivity.EXTRA_MOCK_SERVER_BASE_URL,
            BuildConfig.MOCK_SERVER_BASE_URL,
        ),
    )

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val screenshotDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "screenshots-compose-multiplatform").apply { mkdirs() }
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "$name.png"))
    }

    @Test
    fun composeMultiplatformVariant_initShowModalShowInline() {
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
        device.wait(Until.hasObject(By.textContains("Mock question for")), 8_000)
        screenshot("03-inline-form")
    }
}
