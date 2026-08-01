package com.encatch.composesample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView

@Composable
actual fun EncatchInlineFormHost(formId: String, modifier: Modifier) {
    val factory = IOSNativeViewBridge.inlineFormViewFactory
    if (factory != null) {
        UIKitView(
            factory = { factory(formId) },
            modifier = modifier,
        )
    } else {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("IOSNativeViewBridge.inlineFormViewFactory not set")
        }
    }
}
