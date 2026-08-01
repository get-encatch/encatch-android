package com.encatch.sampleapp

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.encatch.core.Encatch
import kotlinx.coroutines.launch

/**
 * Minimal manual-verification harness — exercises init/identify/showForm and prints
 * SDK events. Not part of the automated test suite (see :core and :android unit tests).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val statusText = findViewById<TextView>(R.id.statusText)

        Encatch.on { eventType, payload ->
            runOnUiThread {
                statusText.text = "Event: ${eventType.wireValue} (formId=${payload.formId})"
            }
        }

        findViewById<Button>(R.id.initButton).setOnClickListener {
            lifecycleScope.launch {
                Encatch.init("YOUR_API_KEY", com.encatch.core.EncatchConfig(debugMode = true))
                statusText.text = "Initialized: ${Encatch.isInitialized}, deviceId=${Encatch.deviceId}"
            }
        }

        findViewById<Button>(R.id.identifyButton).setOnClickListener {
            if (!Encatch.isInitialized) {
                Toast.makeText(this, "Init the SDK first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                Encatch.identifyUser("sample-user-1")
                statusText.text = "Identified as sample-user-1"
            }
        }

        findViewById<Button>(R.id.showFormButton).setOnClickListener {
            if (!Encatch.isInitialized) {
                Toast.makeText(this, "Init the SDK first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                Encatch.showForm("your-form-id")
            }
        }
    }
}
