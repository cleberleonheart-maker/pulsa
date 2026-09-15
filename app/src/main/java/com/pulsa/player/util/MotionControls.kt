package com.pulsa.player.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Controles por movimento (sensores):
 *  - Agitar o aparelho (shake) → trocar de música.
 *  - Inclinar para os lados → ajustar o volume.
 * Ativo apenas quando Settings.gesturesOn estiver ligado.
 */
object MotionControls {

    private const val SHAKE_TRIGGER_G = 2.1f
    private const val SHAKE_WINDOW_MS = 700L
    private const val TILT_EDGE = 6.0f
    private const val TILT_HOLD_MS = 1200L
    private const val TILT_STEP_MS = 450L

    private var active = false
    private var manager: SensorManager? = null
    private var sensor: Sensor? = null
    private var onShakeCb: (() -> Unit)? = null
    private var onTiltCb: ((Int) -> Unit)? = null

    private var lastShakeMs = 0L
    private var shakeCount = 0
    private var tiltDir = 0
    private var tiltSinceMs = 0L
    private var lastStepMs = 0L

    private val listener = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        override fun onSensorChanged(event: SensorEvent) {
            if (!active) return
            val now = System.currentTimeMillis()
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
            if (magnitude > SHAKE_TRIGGER_G * SensorManager.GRAVITY_EARTH) {
                if (now - lastShakeMs > 180L) {
                    lastShakeMs = now
                    shakeCount++
                }
            } else if (now - lastShakeMs > SHAKE_WINDOW_MS) {
                shakeCount = 0
            }
            if (shakeCount >= 2) {
                shakeCount = 0
                onShakeCb?.invoke()
            }

            val dir = when {
                x > TILT_EDGE -> 1
                x < -TILT_EDGE -> -1
                else -> 0
            }
            if (dir != tiltDir) {
                tiltDir = dir
                tiltSinceMs = if (dir == 0) 0L else now
                lastStepMs = now
            }
            if (dir != 0 && now - tiltSinceMs >= TILT_HOLD_MS && now - lastStepMs >= TILT_STEP_MS) {
                lastStepMs = now
                onTiltCb?.invoke(dir)
            }
        }
    }

    fun attach(context: Context, onShake: () -> Unit, onTilt: (Int) -> Unit) {
        onShakeCb = onShake
        onTiltCb = onTilt
        if (!Settings.gesturesOn(context)) return
        val am = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val s = am.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        manager = am
        sensor = s
        active = true
        am.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) {
            val am = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val s = sensor ?: am.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensor = s
            manager = am
            active = true
            am.registerListener(listener, s, SensorManager.SENSOR_DELAY_UI)
        } else {
            detach()
        }
    }

    fun detach() {
        active = false
        reset()
        runCatching { manager?.unregisterListener(listener) }
        manager = null
        sensor = null
    }

    /** Ao retomar a tela depois de uma pausa momentânea, religa se o gesto estiver ativo. */
    fun attachIfEnabled(context: Context, onShake: () -> Unit, onTilt: (Int) -> Unit) {
        if (Settings.gesturesOn(context)) {
            attach(context, onShake, onTilt)
        }
    }

    private fun reset() {
        lastShakeMs = 0L
        shakeCount = 0
        tiltDir = 0
        tiltSinceMs = 0L
        lastStepMs = 0L
    }
}