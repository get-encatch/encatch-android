package com.encatch.sdk.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Renders the Encatch form inline within the composition — no modal, no overlay. Place it
 * anywhere in a Compose Multiplatform layout, mirroring `EncatchInlineFormView` on Android /
 * `ios-native`'s `EncatchInlineFormView` on iOS.
 *
 * This is `compose-sample`'s former internal `EncatchInlineFormHost` promoted to a real,
 * customer-facing composable — renamed since "host" reads as an implementation detail, not
 * something a customer should have to know about.
 *
 * Routing (exact match on [formId] vs. wildcard vs. modal fallback) is resolved by the platform
 * SDK the same way it is for the native Android/iOS inline views this wraps — see
 * `android/src/main/kotlin/com/encatch/android/EncatchInlineFormView.kt`'s class doc for the
 * canonical explanation.
 *
 * Callers do not need to call `EncatchFormHost.install(...)`/`installFormHost()` themselves on
 * either platform — see this file's platform `actual`s (`EncatchInlineForm.android.kt` /
 * `EncatchInlineForm.ios.kt`) for how each platform gets the modal form host installed
 * automatically.
 */
@Composable
expect fun EncatchInlineForm(formId: String, modifier: Modifier = Modifier)
