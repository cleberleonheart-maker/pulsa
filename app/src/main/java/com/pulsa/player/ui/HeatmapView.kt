package com.pulsa.player.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.pulsa.player.R

class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val data = IntArray(168)
    private val max = IntArray(1)
    private val paint = Paint().apply { isAntiAlias = true }
    private var accentColor = 0
    private var dimColor = 0

    fun setAccent(color: Int, dim: Int) {
        accentColor = color
        dimColor = dim
    }

    fun setData(hoursByDow: IntArray) {
        if (hoursByDow.size != 168) return
        hoursByDow.copyInto(data)
        var m = 0
        for (v in data) if (v > m) m = v
        max[0] = m
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 0.42f).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val gap = 3f * resources.displayMetrics.density
        val cell = (w - gap * 25f) / 24f
        val rowH = (h - gap * 8f) / 7f
        val accent = if (accentColor != 0) accentColor
        else androidx.core.content.ContextCompat.getColor(context, R.color.primary)
        val dim = if (dimColor != 0) dimColor
        else androidx.core.content.ContextCompat.getColor(context, R.color.neon_border_soft)

        for (dow in 0 until 7) {
            for (hour in 0 until 24) {
                val count = data[dow * 24 + hour]
                val left = hour * (cell + gap)
                val top = dow * (rowH + gap)
                paint.color = if (max[0] == 0) dim
                else {
                    val alpha = if (count == 0) 18 else (40 + 210f * count / max[0]).toInt()
                    accent and 0x00FFFFFF or (alpha shl 24)
                }
                canvas.drawRoundRect(
                    left, top, left + cell, top + rowH, 2f, 2f, paint
                )
            }
        }
    }
}