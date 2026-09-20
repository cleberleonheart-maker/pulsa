package com.pulsa.player.audio

import android.os.Handler
import android.os.Looper
import com.pulsa.player.playback.Playback

object SleepTimer {

    interface Listener {
        fun onSleepActive(remainingMs: Long)
        fun onSleepDone()
        fun onSleepCancelled()
    }

    @Volatile private var remainingMs = 0L
    @Volatile private var endOfTrack = false
    @Volatile private var active = false
    @Volatile private var listener: Listener? = null
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            remainingMs -= 500
            if (remainingMs <= 0L) {
                finish()
            } else {
                listener?.onSleepActive(remainingMs)
                handler.postDelayed(this, 500)
            }
        }
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    fun schedule(minutes: Int) {
        cancelSilent()
        endOfTrack = false
        active = true
        remainingMs = minutes * 60_000L
        handler.removeCallbacks(tick)
        listener?.onSleepActive(remainingMs)
        handler.postDelayed(tick, 500)
    }

    fun scheduleEndOfTrack() {
        cancelSilent()
        endOfTrack = true
        active = true
        listener?.onSleepActive(-1L)
    }

    fun cancel() {
        cancelSilent()
        listener?.onSleepCancelled()
    }

    private fun cancelSilent() {
        active = false
        endOfTrack = false
        handler.removeCallbacks(tick)
    }

    fun finish() {
        active = false
        endOfTrack = false
        handler.removeCallbacks(tick)
        Playback.pause()
        listener?.onSleepDone()
    }

    fun isActive(): Boolean = active

    fun isEndOfTrack(): Boolean = endOfTrack

    fun remainingMs(): Long = if (active && !endOfTrack) remainingMs else 0L

    fun onTrackCompleted() {
        if (active && endOfTrack) finish()
    }
}
