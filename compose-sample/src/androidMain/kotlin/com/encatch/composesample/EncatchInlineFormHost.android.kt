package com.encatch.composesample

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.encatch.android.EncatchInlineFormView

@Composable
actual fun EncatchInlineFormHost(formId: String, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> EncatchInlineFormView(context).apply { this.formId = formId } },
        update = { view -> view.formId = formId },
    )
}
