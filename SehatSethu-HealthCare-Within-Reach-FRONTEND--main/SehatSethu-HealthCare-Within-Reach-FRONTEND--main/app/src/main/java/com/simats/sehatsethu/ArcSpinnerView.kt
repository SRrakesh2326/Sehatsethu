package com.simats.sehatsethu

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin

/**
 * Premium Netflix/Amazon-like circular loader.
 * - NO background overlay
 * - Smooth rotating arc + changing sweep
 */
class ArcSpinnerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()
    private var anim: ValueAnimator? = null

    // animation state
    private var t: Float = 0f

    // sizing
    private val strokePx: Float
        get() = dp(3.5f)

    private val padPx: Float
        get() = dp(2.5f)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (anim?.isRunning == true) return
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stop() {
        anim?.cancel()
        anim = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val size = minOf(w, h)

        val left = (w - size) / 2f + padPx
        val top = (h - size) / 2f + padPx
        val right = left + size - 2f * padPx
        val bottom = top + size - 2f * padPx

        rect.set(left, top, right, bottom)
        paint.strokeWidth = strokePx

        // nice gradient arc (premium look)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(
                0xFF111827.toInt(), // dark
                0xFF64748B.toInt(), // muted
                0xFF111827.toInt()
            ),
            floatArrayOf(0f, 0.6f, 1f)
        )
        paint.shader = shader

        // rotation + dynamic sweep for "Netflix-like" feel
        val rotation = 360f * t
        val sweep = 60f + 260f * abs(sin(Math.PI * t)).toFloat()
        val startAngle = -90f + rotation

        canvas.save()
        canvas.rotate(rotation, cx, cy)
        canvas.drawArc(rect, startAngle, sweep, false, paint)
        canvas.restore()
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
