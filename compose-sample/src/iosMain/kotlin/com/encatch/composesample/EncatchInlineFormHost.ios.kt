package com.encatch.composesample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.encatch.iosnativeui.EncatchNativeInlineFormView

@Composable
actual fun EncatchInlineFormHost(formId: String, modifier: Modifier) {
    UIKitView(
        factory = { EncatchNativeInlineFormView().apply { this.formId = formId } },
        modifier = modifier,
        update = { view -> view.formId = formId },
    )
}
