package com.pulsa.player.ui
import com.pulsa.player.core.Settings

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class StarsView(context: Context) : View(context) {

    private inner class Star {
        var x0 = 0f
        var y0 = 0f
        var radius = 1f
        var phase = 0f
        var twinkle = 1f
        var drift = 0f
        var weight = 1f
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEAF6FF.toInt() }
    private val stars = mutableListOf<Star>()
    private val random = Random(System.nanoTime())
    private val baseNanos = System.nanoTime()

    private var running = false
    private var opacity = 0.6f
    private var drawWidth = 0
    private var drawHeight = 0

    // Laço de frames próprio: ao contrário do ValueAnimator, NÃO é desligado pela
    // escala global de animação ("remover animações"/economia de bateria, comum no
    // MIUI/HyperOS) — que era o motivo das estrelas ficarem paradas.
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

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
                    x0 = random.nextFloat()
                    y0 = random.nextFloat()
                    radius = size * densityFactor * (0.6f + random.nextFloat() * 0.9f)
                    phase = random.nextFloat() * PI.toFloat() * 2f
                    twinkle = 0.5f + random.nextFloat() * 1.3f
                    drift = (random.nextFloat() - 0.5f) * 0.02f
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

    override fun onDetachedFromWindow() {
        stopTween()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        if (drawWidth <= 0 || drawHeight <= 0) return
        val seconds = (System.nanoTime() - baseNanos) / 1_000_000_000f
        val twoPi = (PI * 2).toFloat()
        for (star in stars) {
            val glow = 0.5f + 0.5f * sin(seconds * star.twinkle * twoPi + star.phase)
            val alpha = (0.10f + 0.7f * glow) * star.weight * opacity
            paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
            if (paint.alpha <= 0) continue
            var x = (star.x0 + star.drift * seconds) % 1f
            if (x < 0f) x += 1f
            var r = star.radius
            if (star.weight > 0.9f) {
                r += 0.8f * densityScale() * glow
            }
            canvas.drawCircle(x * drawWidth, star.y0 * drawHeight, r, paint)
        }
    }

    private fun densityScale(): Float =
        resources.displayMetrics.density.coerceAtLeast(1f)

    fun startTween() {
        if (running) return
        running = true
        removeCallbacks(tick)
        postOnAnimation(tick)
    }

    fun stopTween() {
        running = false
        removeCallbacks(tick)
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
