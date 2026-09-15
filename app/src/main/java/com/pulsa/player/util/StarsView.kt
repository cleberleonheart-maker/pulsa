package com.pulsa.player.util

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class StarsView(context: Context) : View(context) {

    private inner class Star {
        var fx = 0f
        var fy = 0f
        var radius = 1f
        var phase = 0f
        var speed = 1f
        var weight = 1f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFDCD6FF.toInt() }
    private val stars = mutableListOf<Star>()
    private val random = Random(System.nanoTime())
    private var t = 0f
    private var animator: ValueAnimator? = null

    private var opacity = 0.55f
    private var drawWidth = 0
    private var drawHeight = 0

    init {
        isClickable = false
        isFocusable = false
        generateStars()
    }

    private fun generateStars() {
        stars.clear()
        val density = resources.displayMetrics.density
        val densityFactor = (density * 0.5f).coerceIn(0.8f, 2.5f)
        val layers = listOf(
            70 to 0.35f,
            22 to 0.8f,
            5 to 1.7f
        )
        for ((count, size) in layers) {
            repeat(count) {
                stars += Star().apply {
                    fx = random.nextFloat()
                    fy = random.nextFloat()
                    radius = size * densityFactor * (0.6f + random.nextFloat() * 0.9f)
                    phase = random.nextFloat() * PI.toFloat() * 2f
                    speed = 0.6f + random.nextFloat() * 1.4f
                    weight = 0.5f + random.nextFloat() * 0.5f
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        drawWidth = w
        drawHeight = h
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        refresh()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawWidth <= 0 || drawHeight <= 0) return
        val value = t * PI.toFloat() * 2f
        for (star in stars) {
            val twinkle = abs(sin(value * star.speed + star.phase)).toFloat()
            val alpha = (0.10f + 0.55f * twinkle) * star.weight * opacity
            paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            if (paint.alpha <= 0) continue
            var r = star.radius
            if (star.weight > 0.9f) {
                r += 0.6f * densityScale() * twinkle
            }
            canvas.drawCircle(star.fx * drawWidth, star.fy * drawHeight, r, paint)
        }
    }

    private fun densityScale(): Float =
        resources.displayMetrics.density.coerceAtLeast(1f)

    fun startTween() {
        stopTween()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 5000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                t = (a.animatedValue as Float) % 1f
                invalidate()
            }
        }.also { it.start() }
    }

    fun stopTween() {
        animator?.cancel()
        animator = null
    }

    fun refresh() {
        val on = Settings.starsOn(context)
        visibility = if (on) View.VISIBLE else View.GONE
        if (on) startTween() else stopTween()
    }

    companion object {
        private const val TAG = "pulsa_stars"

        fun attach(activity: Activity) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val existing = content.findViewWithTag<StarsView>(TAG)
            if (existing != null) {
                existing.refresh()
                return
            }
            val stars = StarsView(activity)
            stars.tag = TAG
            stars.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            content.addView(stars)
            stars.refresh()
        }

        fun pause(activity: Activity) {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            content.findViewWithTag<StarsView>(TAG)?.stopTween()
        }
    }
}