package com.encatch.android

import android.net.Uri

/** Builds the hosted-form WebView URL, mirrors `buildFormWebViewUrl` from `form-webview-helpers.ts`. */
internal fun buildFormWebViewUrl(
    webHost: String,
    formId: String,
    instanceKey: Int,
    debugMode: Boolean,
    presentation: String = "modal",
): String {
    val builder = Uri.parse("$webHost/s/react-native-sdk-form").buildUpon()
        .appendQueryParameter("formId", formId)
        .appendQueryParameter("ts", instanceKey.toString())
    if (debugMode) builder.appendQueryParameter("debug", "true")
    if (presentation == "inline") builder.appendQueryParameter("presentation", "inline")
    return builder.build().toString()
}
