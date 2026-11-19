package com.encatch.sdk.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.encatch.android.EncatchFormHost
import com.encatch.android.EncatchInlineFormView

/**
 * Android `actual`: the platform-native [EncatchInlineFormView] in an [AndroidView].
 *
 * The modal form host ([EncatchFormHost]) does not depend on this composable: `com.encatch:android`
 * installs it at process start through AndroidX App Startup, so an app with modal surveys only
 * (no inline form on screen) still gets them. The [EncatchFormHost.attach] below is a fallback for
 * an app that removed App Startup's provider: it installs the host if needed and hands it this
 * already-resumed screen, which a host installed this late would otherwise not know. It is a no-op
 * when the host is installed and already knows a screen.
 */
@Composable
actual fun EncatchInlineForm(formId: String?, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EncatchFormHost.attach(context)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> EncatchInlineFormView(ctx).apply { this.formId = formId } },
        update = { view -> view.formId = formId },
    )
}
