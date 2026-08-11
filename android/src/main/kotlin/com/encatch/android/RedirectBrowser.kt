package com.encatch.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.encatch.core.RedirectOpener

/**
 * Opens URLs via Chrome Custom Tabs (in-app browser) or the system browser, mirroring
 * `redirect-browser.ts`'s `openRedirectInternalUrl` / `Linking.openURL` split.
 */
class RedirectBrowser(private val context: Context) : RedirectOpener {

    /** Opens [url] in Custom Tabs without detaching from the host app's task (matches `createTask:false`). */
    override suspend fun openInternal(url: String) {
        val intent = CustomTabsIntent.Builder().build()
        intent.intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        runCatching { intent.launchUrl(context, Uri.parse(url)) }
    }

    /** Opens [url] in the system browser (equivalent to `Linking.openURL`). */
    fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
