package com.encatch.kmpsample

import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.encatch.android.EncatchInlineFormView
import kotlinx.coroutines.launch

/**
 * Launcher entry point for the KMP host sample app variant. Registered by the consuming app's
 * manifest (see `sample-app`'s `AndroidManifest.xml`), since library modules can't declare
 * launcher activities themselves. Plain programmatic Views (no layout XML, no Compose) — this
 * variant is about the shared-`commonMain` dependency structure, not UI technology, so it
 * deliberately looks different from variants 1/2/4.
 */
class KmpSampleMainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MOCK_SERVER_BASE_URL = "mock_server_base_url"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 forces edge-to-edge by default; this hand-built root doesn't consume
        // WindowInsets, so without this the status text renders behind the action bar.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val mockBaseUrl = intent.getStringExtra(EXTRA_MOCK_SERVER_BASE_URL)?.takeIf { it.isNotEmpty() }

        val statusText = TextView(this).apply { text = "Not initialized" }
        val initButton = Button(this).apply { text = "Init SDK"; isAllCaps = false }
        val modalButton = Button(this).apply { text = "Show modal form"; isAllCaps = false }
        val inlineFormLabel = TextView(this).apply { text = "Inline form slot:" }
        val inlineForm = EncatchInlineFormView(this).apply {
            formId = "kmp-inline-form-id"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        val inlineButton = Button(this).apply { text = "Show inline form"; isAllCaps = false }

        initButton.setOnClickListener {
            lifecycleScope.launch { statusText.text = SampleAppController.initSdk(mockBaseUrl) }
        }
        modalButton.setOnClickListener {
            lifecycleScope.launch { statusText.text = SampleAppController.showModalForm() }
        }
        inlineButton.setOnClickListener {
            lifecycleScope.launch { statusText.text = SampleAppController.showInlineForm() }
        }

        // fitsSystemWindows must live on a wrapper with no padding of its own — the framework's
        // inset dispatch overwrites setPadding() on whichever view consumes it, which would
        // otherwise stomp the 24dp content padding below.
        val insetWrapper = FrameLayout(this).apply { fitsSystemWindows = true }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val spacing = (16 * resources.displayMetrics.density).toInt()
            listOf(statusText, initButton, modalButton, inlineFormLabel, inlineForm, inlineButton).forEach { view ->
                addView(view)
                (view.layoutParams as? LinearLayout.LayoutParams)?.topMargin = spacing
            }
        }
        insetWrapper.addView(root)
        setContentView(insetWrapper)
    }
}
