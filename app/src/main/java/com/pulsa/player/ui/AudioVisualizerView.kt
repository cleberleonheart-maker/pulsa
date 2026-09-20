package com.pulsa.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.pulsa.player.R
import com.pulsa.player.audio.MusicVisualizer
import com.pulsa.player.core.Settings

class AudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator: ValueAnimator? = null
    private var lastPeak = 0f
    private var animated = 0f
    private var barWidth = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        barWidth = (w / 60f).coerceAtLeast(2f).coerceAtMost(10f)
        buildPaints()
    }

    private fun buildPaints() {
        val color = accentColor()
        val top = color and 0x00FFFFFF or 0xCC000000.toInt()
        barPaint.shader = LinearGradient(
            0f, height.toFloat(), 0f, 0f,
            intArrayOf(top, top, color),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.color = color and 0x00FFFFFF or 0x18000000.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!MusicVisualizer.on || !Settings.visualizerOn(context)) return
        val peak = MusicVisualizer.peak()
        val target = if (peak > lastPeak) peak else peak * 0.72f + lastPeak * 0.28f
        lastPeak = target
        animated += (target - animated) * 0.12f

        val bars = MusicVisualizer.bars
        if (bars.isEmpty()) return
        val gap = (barWidth * 0.4f).coerceAtLeast(1f)
        val step = barWidth + gap
        val maxBarH = height.coerceAtLeast(4) - 4
        val density = resources.displayMetrics.density

        for (i in bars.indices) {
            val norm = bars[i].coerceIn(0f, 1f)
            val mirrored = 0.55f + 0.45f * norm
            val barH = (norm * maxBarH).coerceAtLeast(2f)
            val alpha = (128 + 127 * mirrored).toInt()
            barPaint.alpha = alpha.coerceIn(0, 255)
            val x = paddingLeft + i * step + width * 0.01f

            val top = height - barH
            val maxBarHeight = height * 0.28f
            canvas.drawRect(
                x, top, x + barWidth,
                height - 2 * density, barPaint
            )
            canvas.drawRect(
                x, 2 * density, x + barWidth,
                (height * 0.04f + bars[i].coerceIn(0f, 1f) * maxBarHeight), glowPaint
            )
        }
    }

    private fun accentColor(): Int = androidx.core.content.ContextCompat.getColor(context, R.color.primary)

    fun startTween() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 60
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                invalidate()
            }
        }.also { it.start() }
    }

    fun stopTween() {
        animator?.cancel()
        animator = null
        lastPeak = 0f
        animated = 0f
    }

    fun refresh(skin: String) {
        if (Settings.visualizerOn(context)) {
            visibility = View.VISIBLE
            startTween()
        } else {
            visibility = View.GONE
            stopTween()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopTween()
    }
}
