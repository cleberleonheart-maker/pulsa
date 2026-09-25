package com.pulsa.player.ui

import com.pulsa.player.R

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import android.util.AttributeSet
import android.util.TypedValue
import org.xmlpull.v1.XmlPullParser

/**
 * Chassis Radar · painel de vidro com canto cortado a 45° (chanfro terminal).
 *
 * Substitui as superfícies estáticas que o `<shape>` só permite arredondar
 * (rows, inputs, cápsulas, decks). O corte é fixo em dp, independente do
 * tamanho do painel — o chanfrado acompanha o esqueleto `ShapeAppearance.Pulsa.PanelCut`.
 *
 * Uso (usado direto como raiz de um drawable XML):
 * <com.pulsa.player.ui.PanelCutDrawable
 *     app:panelColor="@color/glass_fill"
 *     app:panelStroke="@color/neon_border"
 *     app:panelStrokeWidth="1.5dp"
 *     app:panelCut="10dp" />
 */
class PanelCutDrawable : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private var cutPx = dp(10f)
    private var halfStrokePx = 0f

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?
    ) {
        super.inflate(r, parser, attrs, theme)
        parse(r, attrs)
    }

    @Suppress("DEPRECATION")
    override fun inflate(r: Resources, parser: XmlPullParser, attrs: AttributeSet) {
        super.inflate(r, parser, attrs)
        parse(r, attrs)
    }

    private fun parse(r: Resources, attrs: AttributeSet) {
        val ta = r.obtainAttributes(attrs, R.styleable.PanelCut)
        fillPaint.color = ta.getColor(R.styleable.PanelCut_panelColor, ResourcesCompat.getColor(r, R.color.surface, null))
        strokePaint.color = ta.getColor(R.styleable.PanelCut_panelStroke, ResourcesCompat.getColor(r, R.color.neon_border_soft, null))
        strokePaint.strokeWidth =
            ta.getDimensionPixelOffset(R.styleable.PanelCut_panelStrokeWidth, dp(1f).toInt()).toFloat()
        cutPx = ta.getDimensionPixelOffset(R.styleable.PanelCut_panelCut, dp(10f).toInt())
            .toFloat()
        ta.recycle()
        halfStrokePx = strokePaint.strokeWidth / 2f
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuildPath()
    }

    private fun rebuildPath() {
        path.reset()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val cut = minOf(cutPx, w / 2f, h / 2f)
        val inset = halfStrokePx
        val x0 = inset
        val y0 = inset
        val x1 = w - inset
        val y1 = h - inset
        path.moveTo(x0 + cut, y0)
        path.lineTo(x1 - cut, y0)
        path.lineTo(x1, y0 + cut)
        path.lineTo(x1, y1 - cut)
        path.lineTo(x1 - cut, y1)
        path.lineTo(x0 + cut, y1)
        path.lineTo(x0, y1 - cut)
        path.lineTo(x0, y0 + cut)
        path.close()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            Resources.getSystem().displayMetrics
        )
}