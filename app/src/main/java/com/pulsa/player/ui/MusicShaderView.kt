package com.pulsa.player.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import com.pulsa.player.R
import com.pulsa.player.audio.MusicVisualizer
import com.pulsa.player.core.Settings
import kotlin.random.Random

class MusicShaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val auroraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var animator: ValueAnimator? = null
    private var t = 0f
    private var level = 0f
    private var w = 0
    private var h = 0
    private val random = Random(System.nanoTime())

    private class Particle {
        var x = 0f
        var y = 0f
        var vx = 0f
        var vy = 0f
        var r = 2f
        var alpha = 0.6f
        var color = Color.WHITE
    }

    private val particles = mutableListOf<Particle>()

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onSizeChanged(width: Int, height: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(width, height, oldw, oldh)
        w = width
        h = height
        val density = resources.displayMetrics.density
        particles.clear()
        repeat(70) {
            particles += Particle().apply {
                x = random.nextFloat() * width
                y = random.nextFloat() * height
                vx = (random.nextFloat() - 0.5f) * density * 0.6f
                vy = (random.nextFloat() - 0.5f) * density * 0.6f
                r = (1.5f + random.nextFloat() * 2.5f) * density
                alpha = 0.3f + random.nextFloat() * 0.5f
                color = if (random.nextBoolean()) {
                    Color.rgb(255, 60 + random.nextInt(80), 140 + random.nextInt(115))
                } else {
                    Color.rgb(30 + random.nextInt(70), 200 + random.nextInt(55), 255)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (w <= 0 || h <= 0) return
        if (!Settings.visualizerOn(context)) return
        level += (MusicVisualizer.peak().coerceIn(0f, 1f) - level) * 0.2f
        when (Settings.skin(context)) {
            Settings.SKIN_AURORA -> drawAurora(canvas)
            Settings.SKIN_PARTICLES -> drawParticles(canvas)
            Settings.SKIN_NEBULA -> drawNebula(canvas)
            else -> if (Settings.skin(context) != Settings.SKIN_OFF) drawNeon(canvas)
        }
    }

    private fun drawAurora(canvas: Canvas) {
        val accent = accentColor()
        val c1 = Color.rgb(
            (Color.red(accent) * 0.3f).toInt(),
            (Color.green(accent) * 0.2f).toInt(),
            (Color.blue(accent) * 0.9f).toInt()
        )
        val c2 = Color.rgb(
            (Color.red(accent) * 0.9f).toInt(),
            (Color.green(accent) * 0.4f).toInt(),
            (Color.blue(accent) * 0.7f).toInt()
        )
        val drift = t * 2f
        auroraPaint.shader = LinearGradient(
            0f, (h * (0.35f + 0.15f * level)).coerceAtLeast(0f) + drift,
            w.toFloat(), h - drift,
            intArrayOf(c1.and(0x00FFFFFF).or(0x22000000), c2.and(0x00FFFFFF).or(0x40000000)),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), auroraPaint)

        ringPaint.strokeWidth = (2f + 10f * level)
        ringPaint.color = accent.and(0x00FFFFFF).or(((0.12f * level).coerceIn(0f, 0.25f) * 255).toInt().shl(24))
        val cx = w * 0.5f
        val cy = h * 0.28f
        canvas.drawCircle(cx, cy, (60 + 60 * level + t * 40), ringPaint)
    }

    private fun drawParticles(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val speed = (0.6f + level * 1.6f).coerceIn(0.6f, 3f)
        for (p in particles) {
            p.x += p.vx * speed
            p.y += p.vy * speed
            if (p.x < 0) p.x = w.toFloat()
            if (p.x > w) p.x = 0f
            if (p.y < 0) p.y = h.toFloat()
            if (p.y > h) p.y = 0f
            val pulse = 0.6f + 0.4f * MusicVisualizer.peak()
            particlePaint.color = p.color and 0x00FFFFFF or
                ((p.alpha * 255 * pulse).toInt().coerceIn(0, 255).shl(24))
            canvas.drawCircle(p.x, p.y, p.r * (0.8f + 0.4f * pulse), particlePaint)
        }
    }

    private fun drawNeon(canvas: Canvas) {
        val accent = accentColor()
        ringPaint.strokeWidth = (1.5f + 8f * level)
        ringPaint.color = accent.and(0x00FFFFFF).or(((0.10f * level).coerceIn(0f, 0.22f) * 255).toInt().shl(24))
        val cx = w * 0.5f
        val cy = h * 0.30f
        var r = 50f
        repeat(3) {
            canvas.drawCircle(cx, cy, r + t * 30 * (it + 1), ringPaint)
            r += 30
        }
    }

    private fun accentColor(): Int = androidx.core.content.ContextCompat.getColor(context, R.color.primary)

    // Nebulosas: nuvens radiais coloridas em precessão (matiz gira com o tempo) que
    // pulsam com o nível da música — "viagem pelas nebulosas" estilo Nebula.
    private val nebulaPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nebulaCenters = arrayOf(
        floatArrayOf(0.20f, 0.32f, 0.42f),
        floatArrayOf(0.82f, 0.42f, 0.34f),
        floatArrayOf(0.55f, 0.16f, 0.38f),
        floatArrayOf(0.34f, 0.78f, 0.30f),
        floatArrayOf(0.76f, 0.82f, 0.27f)
    )

    private fun drawNebula(canvas: Canvas) {
        val pulse = level.coerceIn(0f, 1f)
        val wMul = (1 + 0.55f * pulse)
        for (i in nebulaCenters.indices) {
            val c = nebulaCenters[i]
            val sign = if (i % 2 == 0) 1f else -1f
            val cx = c[0] * w + t * w * 0.05f * sign
            val cy = c[1] * h + t * h * 0.03f * sign
            val r = c[2] * w * wMul
            val hue = (i * 137.508f + t * 55f + pulse * 25f) % 360f
            val alpha = (20 + 90 * pulse * (0.35f + 0.65f * (i % 3) / 2f)).toInt().coerceIn(16, 110)
            nebulaPaint.shader = RadialGradient(
                cx, cy, r,
                intArrayOf(
                    Color.HSVToColor(alpha, floatArrayOf(hue, 0.8f, 0.95f)),
                    Color.HSVToColor(6, floatArrayOf((hue + 40f) % 360f, 0.7f, 0.55f))
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawOval(cx - r, cy - r, cx + r, cy + r, nebulaPaint)
        }
        // Núcleo brilhante no centro, cresce com a batida
        val coreHue = (t * 55f + 200f) % 360f
        val cr = (w * 0.16f) * (1f + pulse)
        nebulaPaint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, cr,
            intArrayOf(
                Color.HSVToColor((40 + 110 * pulse).toInt().coerceIn(20, 140), floatArrayOf(coreHue, 0.9f, 1f)),
                Color.HSVToColor(0, floatArrayOf(coreHue, 0.8f, 0.5f))
            ),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawOval(w * 0.5f - cr, h * 0.42f - cr * 0.75f, w * 0.5f + cr, h * 0.42f + cr * 0.75f, nebulaPaint)
    }

    fun startTween() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 90
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
        level = 0f
    }

    fun refresh(skin: String) {
        if (skin != Settings.SKIN_OFF && Settings.visualizerOn(context)) {
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
