package com.encatch.android

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * Theme-aware WebView loading overlay, ported from `FormWebViewLoading.tsx`'s
 * `FormWebViewSkeleton`: a pulsing (700ms up/down loop) placeholder — header bar, two text
 * rows, an input block, and a button block — shown from form load until `form:ready`.
 */
class FormWebViewSkeleton @JvmOverloads constructor(
    context: Context,
    private val activeMode: String = "light",
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Int) = v * density

    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (activeMode == "dark") Color.argb(26, 255, 255, 255) else Color.argb(20, 0, 0, 0)
    }

    private var pulseAlpha = 0.4f
    private var pulseAnimator: ValueAnimator? = null

    init {
        setWillNotDraw(false)
    }

    fun setBackgroundArgb(argb: Int) {
        setBackgroundColor(argb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        pulseAnimator = ObjectAnimator.ofFloat(this, "pulseAlphaProp", 0.4f, 1f).apply {
            duration = 700
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    @Suppress("unused") // driven reflectively by ObjectAnimator via the "pulseAlphaProp" property name
    fun setPulseAlphaProp(value: Float) {
        pulseAlpha = value
        invalidate()
    }

    fun getPulseAlphaProp(): Float = pulseAlpha

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        rowPaint.alpha = (pulseAlpha * 255).roundToInt().coerceIn(0, 255)

        val paddingH = dp(20)
        val top = dp(28)
        val w = (width - paddingH * 2).coerceAtLeast(0f)

        var y = top
        // Header bar: 16dp tall, 60% wide
        drawBar(canvas, paddingH, y, w * 0.6f, dp(16), dp(8))
        y += dp(16) + dp(24)

        // Question text row (wide): 12dp tall, 90% wide
        drawBar(canvas, paddingH, y, w * 0.9f, dp(12), dp(6))
        y += dp(12) + dp(10)

        // Question text row (narrow): 12dp tall, 65% wide
        drawBar(canvas, paddingH, y, w * 0.65f, dp(12), dp(6))
        y += dp(12) + dp(24)

        // Input block: 44dp tall, full width
        drawBar(canvas, paddingH, y, w, dp(44), dp(10))
        y += dp(44) + dp(20)

        // Button block: 44dp tall, 50% wide, centered
        val buttonWidth = w * 0.5f
        drawBar(canvas, paddingH + (w - buttonWidth) / 2, y, buttonWidth, dp(44), dp(10))
    }

    private fun drawBar(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, radius: Float) {
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), radius, radius, rowPaint)
    }
}
