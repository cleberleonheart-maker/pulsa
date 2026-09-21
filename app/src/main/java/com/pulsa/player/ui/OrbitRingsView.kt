package com.pulsa.player.ui

import com.pulsa.player.core.Settings

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Anéis de órbita estilo satélite/núcleo atômico (camada decorativa).
 * Dois anéis elípticos inclinados giram lentamente com partículas orbitando.
 * Respeita a preferência "fundo animado" do usuário: com ela desligada,
 * desenha apenas os anéis fixos (sem custo de CPU).
 */
class OrbitRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var radius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var angle = 0f

    private val density = resources.displayMetrics.density

    private val ringOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0x8825F4EE.toInt()
    }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * density
        color = 0x5925F4EE.toInt()
    }
    private val ringDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = 0x40FF2E9E.toInt()
    }
    private val particleMagenta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6BC4.toInt() }
    private val particleMagentaGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FF2E9E.toInt() }
    private val particleCyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF25F4EE.toInt() }
    private val particleCyanGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x6625F4EE.toInt() }

    private var animator: ValueAnimator? = null
    private var animated = true

    private val runnableStart = Runnable { sync() }

    init {
        isClickable = false
        isFocusable = false
    }

    private fun sync() {
        animated = Settings.animatedBg(context)
        if (animated) {
            startIfAppropriate()
        } else {
            animator?.cancel()
            animator = null
        }
        invalidate()
    }

    private fun startIfAppropriate() {
        if (animator?.isStarted == true) return
        if (!animated || visibility != View.VISIBLE || !isAttachedSafe()) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 9000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { a ->
                angle = a.animatedValue as Float
                invalidate()
            }
        }.also { it.start() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sync()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        removeCallbacks(runnableStart)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        handleVisibility(visibility)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        handleVisibility(visibility)
    }

    private fun handleVisibility(visibility: Int) {
        if (visibility == View.VISIBLE) {
            removeCallbacks(runnableStart)
            post(runnableStart)
        } else {
            animator?.cancel()
            animator = null
        }
    }

    private fun isAttachedSafe(): Boolean =
        try {
            isAttachedToWindow
        } catch (e: Throwable) {
            true
        }

    private fun rotateFrom(x: Float, y: Float, tiltDeg: Float): Pair<Float, Float> {
        if (tiltDeg == 0f) return x to y
        val t = tiltDeg * (PI.toFloat() / 180f)
        val s = sin(t)
        val c = cos(t)
        val dx = x - centerX
        val dy = y - centerY
        return (centerX + dx * c - dy * s) to (centerY + dx * s + dy * c)
    }

    private fun drawRing(canvas: Canvas, rx: Float, ry: Float, tilt: Float, paint: Paint) {
        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(tilt, 0f, 0f)
        canvas.drawOval(RectF(-rx, -ry, rx, ry), paint)
        canvas.restore()
    }

    private fun drawParticle(
        canvas: Canvas,
        x: Float,
        y: Float,
        glowPaint: Paint,
        corePaint: Paint,
        coreRadius: Float
    ) {
        canvas.drawCircle(x, y, coreRadius * 2.8f, glowPaint)
        canvas.drawCircle(x, y, coreRadius, corePaint)
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return

        val rxO = radius * 0.72f
        val ryO = rxO * 0.32f
        val rxI = rxO * 0.62f
        val ryI = rxI * 0.30f

        drawRing(canvas, rxO, ryO, 18f, ringOuter)
        drawRing(canvas, rxI, ryI, -10f, ringInner)
        drawRing(canvas, rxO * 0.82f, ryO * 0.9f, -24f, ringDash)

        val a = angle * (PI.toFloat() * 2f / 360f)

        val px = centerX + cos(a) * rxO
        val py = centerY + sin(a) * ryO
        val posA = rotateFrom(px, py, 18f)
        drawParticle(canvas, posA.first, posA.second, particleMagentaGlow, particleMagenta, 5f * density)

        val b = a + PI.toFloat()
        val posB = rotateFrom(centerX + cos(b) * rxI, centerY + sin(b) * ryI, -10f)
        drawParticle(canvas, posB.first, posB.second, particleCyanGlow, particleCyan, 4.4f * density)
    }

    /** Reavalia a preferência "fundo animado" (chamado ao voltar pra tela). */
    fun refresh() {
        sync()
    }
}