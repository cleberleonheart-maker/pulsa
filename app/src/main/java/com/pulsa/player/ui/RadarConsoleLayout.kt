package com.pulsa.player.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Console 360° do Pulsa: posiciona os controles (botões existentes) como
 * setores de um anel ao redor do centro (deques/avatar), em vez de coluna vertical.
 * Cada filho cai num ângulo cardeal/intercardeal (0°, 45°, 90° ... 315°) e é
 * girado para olhar o centro, mantendo o visual chanfrado do tema.
 */
class RadarConsoleLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val radius get() = minOf(width, height) / 2f * 0.72f

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val cx = width / 2f
        val cy = height / 2f
        val visible = (0 until childCount).map { getChildAt(it) }
            .filter { it.visibility != View.GONE }
        if (visible.isEmpty()) return
        val step = (2f * Math.PI) / visible.size
        visible.forEachIndexed { i, child ->
            val angle = i * step
            val dx = (cos(angle) * radius).toFloat()
            val dy = (sin(angle) * radius).toFloat()
            val w = child.width
            val h = child.height
            child.layout(
                (cx - w / 2f + dx).toInt(),
                (cy - h / 2f + dy).toInt(),
                (cx - w / 2f + dx + w).toInt(),
                (cy - h / 2f + dy + h).toInt()
            )
            child.rotation = (angle * 180f / Math.PI.toFloat()).toFloat() + 90f
        }
    }
}
