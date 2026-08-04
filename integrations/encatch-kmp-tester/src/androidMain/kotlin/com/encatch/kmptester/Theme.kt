package com.encatch.kmptester

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared visual language for the tester app, mirroring `encatch-ios-tester/Sources/Theme.swift`:
 * the modern Uber rider-app idiom — monochrome black/white "ink" palette that inverts cleanly in
 * dark mode, bold typography, pill-shaped buttons and chips, and softly rounded flat gray tiles.
 * Pure presentation — no SDK calls live here. All colors are resolved against the current UI
 * night mode because the manifest theme is a fixed light framework theme.
 */
object TesterTheme {
    /** Uber's safety green — used sparingly for positive/selected states. */
    const val GREEN = 0xFF06C167.toInt()
    const val RED = 0xFFDC2626.toInt()
    const val CORNER_RADIUS_DP = 16f
}

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

/** Primary "ink": black in light mode, white in dark mode (Uber's monochrome brand color). */
fun Context.ink(): Int = if (isNightMode()) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

/** Plain screen background — Uber screens are white/black, not grouped gray. */
fun Context.surface(): Int = if (isNightMode()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

/** Flat light-gray fill used for cards, chips, secondary buttons and text fields. */
fun Context.surfaceVariant(): Int = if (isNightMode()) 0xFF1C1C1E.toInt() else 0xFFF2F2F4.toInt()

/** Secondary gray text. */
fun Context.secondaryText(): Int = if (isNightMode()) 0xFF9E9EA4.toInt() else 0xFF6B6B70.toInt()

/** Disabled fill (systemGray4 equivalent). */
fun Context.disabledFill(): Int = if (isNightMode()) 0xFF3A3A3C.toInt() else 0xFFD1D1D6.toInt()

fun Context.dp(value: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics).toInt()

/** Fully rounded pill drawable — pass a very large radius so any height renders as a capsule. */
fun Context.pillBackground(color: Int, radiusDp: Float = 100f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

/** Softly rounded flat tile background for cards and text fields. */
fun Context.roundedBackground(color: Int, radiusDp: Float = TesterTheme.CORNER_RADIUS_DP): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

private fun Button.stripDefaultChrome() {
    isAllCaps = false
    stateListAnimator = null
    minHeight = 0
    minimumHeight = 0
    minWidth = 0
    minimumWidth = 0
    gravity = Gravity.CENTER
    backgroundTintList = null
}

/** Solid ink pill button: bold label in the opposite color, full width, generous padding. */
fun Context.primaryButton(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        stripDefaultChrome()
        background = pillBackground(ink())
        setTextColor(surface())
        typeface = Typeface.DEFAULT_BOLD
        textSize = 16f
        setPadding(dp(20f), dp(15f), dp(20f), dp(15f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(8f); bottomMargin = dp(4f) }
        setOnClickListener { onClick() }
    }

/** Gray fill pill button with ink semibold label — Uber's secondary action. */
fun Context.secondaryButton(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        stripDefaultChrome()
        background = pillBackground(surfaceVariant())
        setTextColor(ink())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 15f
        setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(6f); bottomMargin = dp(2f) }
        setOnClickListener { onClick() }
    }

/** Compact gray capsule chip for preset/inline actions. */
fun Context.chipButton(label: String, selected: Boolean = false, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        stripDefaultChrome()
        background = pillBackground(if (selected) ink() else surfaceVariant())
        setTextColor(if (selected) surface() else ink())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 13f
        setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { rightMargin = dp(8f); topMargin = dp(4f); bottomMargin = dp(4f) }
        setOnClickListener { onClick() }
    }

/** Borderless quiet text button for tertiary actions, secondary gray semibold label. */
fun Context.quietButton(label: String, onClick: () -> Unit): Button =
    Button(this).apply {
        text = label
        stripDefaultChrome()
        background = null
        setTextColor(secondaryText())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = 14f
        setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        setOnClickListener { onClick() }
    }

/** Flat gray card, Uber-style: rounded 16dp, no border, no elevation. */
fun Context.card(): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundedBackground(surfaceVariant())
        setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4f); bottomMargin = dp(8f) }
    }

/** Bold section title above a card — Uber uses strong headlines, not gray small-caps. */
fun Context.sectionHeader(text: String): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 17f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ink())
        setPadding(dp(2f), dp(12f), dp(2f), dp(8f))
    }

/** Small bold caption above a text field (labels live above fields, not in hints). */
fun Context.fieldLabel(text: String): TextView =
    TextView(this).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ink())
        setPadding(dp(2f), dp(8f), dp(2f), dp(4f))
    }

/** Flat gray filled text field, 12dp rounded corners, no underline. */
fun Context.filledField(hintText: String? = null): EditText =
    EditText(this).apply {
        hint = hintText
        background = roundedBackground(surfaceVariant(), 12f)
        setTextColor(ink())
        setHintTextColor(secondaryText())
        backgroundTintList = null
        textSize = 15f
        setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4f); bottomMargin = dp(8f) }
    }
