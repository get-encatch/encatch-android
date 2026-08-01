package com.encatch.sampleapp

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.encatch.kmpsample.KmpSampleMainActivity
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Variant 5 counterpart to [ScreenshotFlowTest] — same init -> show modal -> show inline flow,
 * driven against `:kmp-sample`'s `KmpSampleMainActivity` (a KMP module's own Android entry point,
 * calling shared `commonMain` business logic directly rather than through :core's raw API from
 * single-platform app code).
 */
class KmpSampleScreenshotFlowTest {

    @get:Rule
    val activityRule = ActivityScenarioRule<KmpSampleMainActivity>(
        Intent(
            ApplicationProvider.getApplicationContext(),
            KmpSampleMainActivity::class.java,
        ).putExtra(
            KmpSampleMainActivity.EXTRA_MOCK_SERVER_BASE_URL,
            BuildConfig.MOCK_SERVER_BASE_URL,
        ),
    )

    private val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val screenshotDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "screenshots-kmp-sample").apply { mkdirs() }
    }

    private fun screenshot(name: String) {
        device.waitForIdle()
        device.takeScreenshot(File(screenshotDir, "$name.png"))
    }

    @Test
    fun kmpSampleVariant_initShowModalShowInline() {
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
