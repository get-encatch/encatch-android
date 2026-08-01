package com.encatch.android

import android.content.res.Configuration
import android.view.Gravity
import com.encatch.core.HorizontalAnchor
import com.encatch.core.PositionAlignment
import com.encatch.core.VerticalAnchor

/** Android-specific adapters for the platform-neutral appearance types shared via `:core`. */

fun PositionAlignment.toGravity(): Int {
    val vertical = when (vertical) {
        VerticalAnchor.TOP -> Gravity.TOP
        VerticalAnchor.CENTER -> Gravity.CENTER_VERTICAL
        VerticalAnchor.BOTTOM -> Gravity.BOTTOM
    }
    val horizontal = when (horizontal) {
        HorizontalAnchor.START -> Gravity.START
        HorizontalAnchor.CENTER -> Gravity.CENTER_HORIZONTAL
        HorizontalAnchor.END -> Gravity.END
    }
    return vertical or horizontal
}

/** Whether the given `Configuration.uiMode` flags indicate the system is in dark mode. */
fun isConfigurationDark(uiMode: Int): Boolean =
    (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
