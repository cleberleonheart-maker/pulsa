package com.pulsa.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.pulsa.player.R
import com.pulsa.player.util.MusicVisualizer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pinta o avatar da Virgin e o anima conforme a música:
 * balança/bob no ritmo (bass dos primeiros bins do [MusicVisualizer])
 * e, sem música, fica numa respiração calma de "idle".
 */
class DancingVirginView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val avatar = resources.getDrawable(R.drawable.virgin_avatar_animated, null)
    private var sm = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    private val bassEnergy: Float
        get() {
            if (!MusicVisualizer.on) return 0f
            var sum = 0f
            val n = 6
            for (i in 0 until n) sum += MusicVisualizer.bars.getOrElse(i) { 0f }
            return (sum / n).coerceIn(0f, 1f)
        }

    private fun computeSm() {
        val target = bassEnergy
        sm += (target - sm) * 0.25f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        avatar?.setBounds(0, 0, w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val a = avatar ?: return
        val cw = width / 2f
        val cy = height * 0.52f
        computeSm()

        val breathe = 0.5f + 0.5f * kotlin.math.sin(phase * 0.06f)
        val bob = sm * (height * 0.06f) + breathe * height * 0.035f
        val scale = 1f + sm * 0.07f
        val rot = sin(phase) * 5f * (0.12f + sm)
        val sway = cos(phase * 0.35f) * (2f + sm * 4f)

        canvas.save()
        canvas.translate(cw + sway, cy - bob)
        canvas.rotate(rot, 0f, 0f)
        canvas.scale(scale, scale)
        canvas.translate(-cw, -cy)
        a.draw(canvas)
        canvas.restore()

        phase += 0.06f + sm * 0.12f
    }

    private fun startLoop() {
        if (animator?.isRunning == true) return
        (avatar as? AnimatedVectorDrawable)?.start()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
        }.also { it.start() }
    }

    private fun stopLoop() {
        (avatar as? AnimatedVectorDrawable)?.stop()
        animator?.cancel()
        animator = null
        sm = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopLoop()
    }
}