package com.encatch.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Invisible proxy activity that shows the system runtime-permission dialog on behalf of
 * [EncatchWebView]'s `onPermissionRequest` (the web form's video/audio recording questions call
 * `getUserMedia`, whose WebView grant only takes effect when the app itself holds
 * CAMERA/RECORD_AUDIO). Same pattern and rationale as [EncatchFileChooserActivity]: a plain View
 * can't request permissions, and requiring host-app wiring would break drop-in setup.
 */
internal class EncatchPermissionActivity : Activity() {

    companion object {
        private var pendingPermissions: Array<String>? = null
        private var pendingResult: ((Boolean) -> Unit)? = null
        private const val REQUEST_CODE = 0xEC48

        internal fun launch(context: Context, permissions: Array<String>, onResult: (Boolean) -> Unit) {
            // A second request while one is pending resolves the first as denied rather than
            // leaking its callback (which would leave the WebView's PermissionRequest hanging).
            pendingResult?.invoke(false)
            pendingPermissions = permissions
            pendingResult = onResult
            runCatching {
                context.startActivity(
                    Intent(context, EncatchPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure {
                pendingPermissions = null
                pendingResult = null
                onResult(false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val permissions = pendingPermissions
        pendingPermissions = null
        if (permissions == null || pendingResult == null) {
            finishWithResult(false)
            return
        }
        requestPermissions(permissions, REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE) return
        finishWithResult(grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
    }

    private fun finishWithResult(granted: Boolean) {
        pendingResult?.invoke(granted)
        pendingResult = null
        finish()
    }
}
