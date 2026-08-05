package com.encatch.sdk.compose

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.encatch.android.EncatchFormHost
import com.encatch.android.EncatchInlineFormView

/**
 * Android `actual`. Copied from `compose-sample`'s former
 * `EncatchInlineFormHost.android.kt` (now deleted in favour of this library composable), renamed.
 *
 * Also lazily installs the modal form host on first composition — see this file's own doc below
 * for why Android needs this and iOS doesn't.
 *
 * ### Why this asymmetry exists between platforms
 *
 * `:kmp-sdk`'s `Encatch.init(apiKey, config)` is deliberately platform-agnostic `commonMain` API
 * with no `Context`/`Application` parameter (iOS has no equivalent concept, so adding one there
 * would leak an Android-only concern into the shared surface). But Android's
 * [EncatchFormHost.install] needs a real [Application] instance to register
 * `ActivityLifecycleCallbacks` (it tracks the foreground Activity to host the modal form dialog) —
 * so it can't be called from inside `Encatch.init(...)` on Android the way `EncatchBridge
 * .installFormHost()` is called from inside `Encatch.ios.kt`'s `init(...)` on iOS (no
 * Application-equivalent needed there at all).
 *
 * `:compose-sdk` is the natural place to close that gap: unlike a raw `:kmp-sdk` consumer (which
 * might not be Compose, or even might not be an Activity-based app), a `:compose-sdk` customer is
 * always running inside an Android `Activity`/`Application` context by construction. So
 * [EncatchInlineForm]'s Android `actual` grabs `LocalContext.current.applicationContext` on first
 * composition and installs the form host automatically — the customer never has to think about it
 * on either platform, matching the "few lines, no plumbing" goal. This mirrors an app calling
 * `EncatchFormHost.install(application)` from `Application.onCreate()` by hand, just triggered
 * lazily from Compose instead.
 *
 * [EncatchFormHost.install] already no-ops after the first call (`if (installed) return`), so
 * calling it again on every recomposition/every additional [EncatchInlineForm] instance is
 * harmless — no extra guarding needed here beyond `LaunchedEffect(Unit)` limiting it to once per
 * composition of a given call site.
 */
@Composable
actual fun EncatchInlineForm(formId: String?, modifier: Modifier) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EncatchFormHost.install(context.applicationContext as Application)
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx -> EncatchInlineFormView(ctx).apply { this.formId = formId } },
        update = { view -> view.formId = formId },
    )
}
