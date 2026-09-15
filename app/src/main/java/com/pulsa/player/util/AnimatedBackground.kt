package com.pulsa.player.util

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View

object AnimatedBackground {

    private val paletteA = intArrayOf(
        0xFF0B0918.toInt(), 0xFF120E2A.toInt(), 0xFF1A1240.toInt()
    )
    private val paletteB = intArrayOf(
        0xFF150E33.toInt(), 0xFF1E1446.toInt(), 0xFF2A1647.toInt()
    )

    private var target: View? = null
    private var animator: ValueAnimator? = null
    private var last: Activity? = null
    private val gradient = GradientDrawable(GradientDrawable.Orientation.TL_BR, paletteA)

    fun apply(activity: Activity) {
        val view = activity.window.decorView
        view.background = gradient
        target = view
        last = activity
        if (Settings.animatedBg(activity)) {
            if (animator?.isRunning != true) start()
        } else {
            stop()
        }
        StarsView.attach(activity)
    }

    fun stop() {
        animator?.cancel()
        animator = null
        gradient.colors = paletteA
        last?.let(StarsView::pause)
    }

    private fun start() {
        animator?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 14000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                gradient.colors = intArrayOf(
                    lerp(paletteA[0], paletteB[0], f),
                    lerp(paletteA[1], paletteB[1], f),
                    lerp(paletteA[2], paletteB[2], f)
                )
            }
        }
        animator = anim
        anim.start()
    }

    private fun lerp(from: Int, to: Int, f: Float): Int {
        return Color.argb(
            255,
            (Color.red(from) + (Color.red(to) - Color.red(from)) * f).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * f).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * f).toInt()
        )
    }

    fun isAttached(view: View): Boolean = target === view
}