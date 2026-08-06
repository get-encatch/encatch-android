package com.encatch.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ValueCallback

/**
 * Invisible proxy activity that launches the system file chooser on behalf of
 * [EncatchWebView]'s `onShowFileChooser` and routes the result back to the WebView's
 * [ValueCallback]. A plain View can't call `startActivityForResult`, and requiring host apps to
 * forward their own activity results would break the SDK's drop-in setup — so the SDK ships this
 * translucent, no-UI activity instead (declared in the library manifest).
 *
 * The handoff happens via [pendingCallback]/[pendingChooserIntent] statics rather than intent
 * extras because a [ValueCallback] isn't parcelable. Only one chooser can be pending at a time,
 * which matches WebView's own behavior (a new request cancels the previous one).
 */
internal class EncatchFileChooserActivity : Activity() {

    companion object {
        internal var pendingCallback: ValueCallback<Array<Uri>>? = null
        internal var pendingChooserIntent: Intent? = null

        /** Where a camera capture writes; used as the result when the chooser returns no data. */
        internal var pendingCameraOutput: Uri? = null

        private const val REQUEST_CODE = 0xEC47
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chooser = pendingChooserIntent
        pendingChooserIntent = null
        if (chooser == null || pendingCallback == null) {
            finishWithResult(null)
            return
        }
        runCatching { startActivityForResult(chooser, REQUEST_CODE) }
            .onFailure { finishWithResult(null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CODE) return

        val uris: Array<Uri>? = if (resultCode != RESULT_OK) {
            null
        } else {
            val clip = data?.clipData
            when {
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                data?.data != null -> arrayOf(data.data!!)
                // Camera apps return RESULT_OK with an empty intent — the image went to
                // the EXTRA_OUTPUT uri we supplied.
                pendingCameraOutput != null -> arrayOf(pendingCameraOutput!!)
                else -> null
            }
        }

        if (uris == null) {
            finishWithResult(null)
            return
        }

        // The picker's read grant on returned content:// uris dies when this activity
        // finishes — by the time the WebView reads the file it sees "empty or inaccessible".
        // Copy each picked file into our cache while the grant is still alive and hand the
        // WebView uris from our own FileProvider instead (camera output is already ours).
        // Off the main thread: gallery videos can be large.
        Thread {
            val stable = uris.map { rehomeToCache(it) ?: it }.toTypedArray()
            runOnUiThread { finishWithResult(stable) }
        }.start()
    }

    private fun rehomeToCache(uri: Uri): Uri? {
        if (uri == pendingCameraOutput) return uri
        return runCatching {
            val name = contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            } ?: "picked"
            val dir = java.io.File(cacheDir, "encatch_picks").apply { mkdirs() }
            val out = java.io.File(dir, "${System.currentTimeMillis()}_$name")
            contentResolver.openInputStream(uri)!!.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.encatch.fileprovider", out)
        }.getOrNull()
    }

    private fun finishWithResult(uris: Array<Uri>?) {
        // The callback must be invoked exactly once even on cancel — leaking it silently
        // breaks every subsequent file chooser in the WebView.
        pendingCallback?.onReceiveValue(uris)
        pendingCallback = null
        pendingCameraOutput = null
        finish()
    }
}
