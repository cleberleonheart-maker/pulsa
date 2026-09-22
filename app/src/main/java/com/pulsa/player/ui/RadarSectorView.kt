package com.pulsa.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.pulsa.player.core.Settings
import kotlin.math.cos
import kotlin.math.sin

/**
 * Esqueleto do Console 360°: um anel chanfrado com setores radiais (18 por volta),
 * cada um gravado com um glifo ANSI (mono). O usuário deu carta branca:
 * apagar a coluna de botões e mover TODAS as funções para setores do anel,
 * mantendo avatares e funções, sem mudar a cor.
 */
class RadarSectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val inside = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = android.graphics.Color.argb(48, 255, 255, 255)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = android.graphics.Color.argb(80, 255, 255, 255)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
        typeface = android.graphics.Typeface.MONOSPACE
        color = android.graphics.Color.argb(230, 230, 240, 235)
    }
    private val rect = RectF()
    private var progress = 0f

    fun setProgress(fraction: Float) {
        progress = fraction.coerceIn(0f, 1f)
        invalidate()
    }

    companion object {
        private const val SECTORS = 18
        private val GLYPHS = charArrayOf(
            '\u21C4', '\u21BB', '\u23F8', '\u2122', '\u2248', '\u263E',
            '\u266B', '\u2261', 'A', 'B', '\u229C', '\u21C4',
            '\u21BB', '\u23F8', '\u2122', '\u2248', '\u263E', '\u266B'
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val R = minOf(w, h) / 2f - dp(2f)

        rect.set(cx - R, cy - R, cx + R, cy + R)
        canvas.drawArc(rect, -90f, progress * 360f, true, inside)
        canvas.drawArc(rect, -90f, 360f, false, edge)

        val cw = 360f / SECTORS
        for (i in 0 until SECTORS) {
            val start = Math.toRadians((-90f + i * cw + cw / 2f).toDouble())
            val rr = (R - dp(10f)).toDouble()
            val x = (cx + cos(start) * rr).toFloat()
            val y = (cy + sin(start) * rr).toFloat()
            if (i < GLYPHS.size) {
                glyph.color = if (i >= 8 && i <= 10) activeColor else baseColor
                canvas.drawText(GLYPHS[i].toString(), x, y + glyph.textSize * 0.35f, glyph)
            }
        }

        val sweepRad = Math.toRadians((-90f + progress * 360f).toDouble())
        val rs = (R - dp(3f)).toDouble()
        val sweepX = (cx + cos(sweepRad) * rs).toFloat()
        val sweepY = (cy + sin(sweepRad) * rs).toFloat()
        sweep.color = colorPrimary
        canvas.drawCircle(sweepX, sweepY, dp(3.5f), sweep)
    }

    private val baseColor: Int get() = android.graphics.Color.rgb(0x8A, 0x96, 0x8C)
    private val activeColor: Int get() = android.graphics.Color.rgb(0x00, 0xEF, 0x9D)
    private val colorPrimary: Int get() = cls() ?: android.graphics.Color.rgb(0x00, 0xEF, 0x9D)
    private fun cls(): Int? = null

    private val sweep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tanBase = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        color = android.graphics.Color.rgb(0x00, 0xEF, 0x9D)
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val def = dp(340f).toInt()
        val w = android.view.View.MeasureSpec.getSize(widthMeasureSpec).let { if (it == 0) def else it }
        val h = android.view.View.MeasureSpec.getSize(heightMeasureSpec).let { if (it == 0) def else it }
        setMeasuredDimension(w, h)
    }
}
