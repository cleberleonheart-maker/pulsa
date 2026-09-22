package com.pulsa.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.AnimatedVectorDrawable
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.pulsa.player.R
import com.pulsa.player.audio.MusicVisualizer
import com.pulsa.player.core.Settings
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pinta o avatar da Virgin (feminino ou masculino) e o anima conforme a música:
 * balança/bob no ritmo (bass dos primeiros bins do [MusicVisualizer])
 * e, sem música, fica numa respiração calma de "idle".
 */
class DancingVirginView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var avatar: AnimatedVectorDrawable? = null
    private var sm = 0f
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** Força um gênero específico; null segue o avatar ativo em [Settings]. */
    private var requestedMale: Boolean? = null

    var forceMale: Boolean?
        get() = requestedMale
        set(value) {
            if (requestedMale == value) return
            requestedMale = value
            if (isAttachedToWindow) refreshAvatar()
        }

    companion object {
        private val views = mutableSetOf<DancingVirginView>()

        fun refreshAll() {
            views.toList().forEach { it.refreshAvatar() }
        }
    }

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

    private fun refreshAvatar() {
        val male = forceMale ?: Settings.masculineAvatar(context)
        val res = if (male) R.drawable.avatar_masculino_animated else R.drawable.virgin_avatar_animated
        val fresh = resources.getDrawable(res, null) as AnimatedVectorDrawable
        avatar?.stop()
        avatar = fresh
        if (width > 0 && height > 0) fresh.setBounds(0, 0, width, height)
        if (animator?.isRunning == true) fresh.start()
        invalidate()
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

        // Sem música: flutua suavemente subindo/descendo ~5dp a cada ~3s (estilo TAMI),
        // com o blink do vector. Com música: a fase acelera e o corpo dança mais forte.
        val float = 0.5f + 0.5f * sin(phase)
        val bob = sm * (height * 0.09f) + float * height * 0.05f
        val scale = 1f + sm * 0.06f
        val rot = sin(phase) * (1f + sm * 4f)
        val sway = cos(phase * 0.5f) * (2f + sm * 4f)

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
        if (avatar == null) refreshAvatar()
        if (animator?.isRunning == true) return
        avatar?.start()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { invalidate() }
        }.also { it.start() }
    }

    private fun stopLoop() {
        avatar?.stop()
        animator?.cancel()
        animator = null
        sm = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        views.add(this)
        startLoop()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        views.remove(this)
        stopLoop()
    }
}
