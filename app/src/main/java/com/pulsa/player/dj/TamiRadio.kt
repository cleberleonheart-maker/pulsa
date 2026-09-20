package com.pulsa.player.dj

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.pulsa.player.R
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.playback.Playback
import com.pulsa.player.sync.Telemetry
import java.util.Calendar

/**
 * Modo "Rádio TAMI": embaralha todas as músicas e toca sem parar, com
 * saudação + hora em ponto a cada virada de hora (sem clima), igual à TAMI.
 * Não interfere no rádio de estações (RadioActivity) nem na locutora do DJ.
 */
object TamiRadio {

    private const val POLL_MS = 30_000L

    private val handler = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var voice: DjVoice? = null
    private var lastHour = -1

    @Volatile
    private var active = false

    @Volatile
    var onChange: (() -> Unit)? = null

    val isActive: Boolean
        get() = active

    private val tick = object : Runnable {
        override fun run() {
            handler.postDelayed(this, POLL_MS)
            if (!active) return
            val now = Calendar.getInstance()
            val hour = now.get(Calendar.HOUR_OF_DAY)
            if (now.get(Calendar.MINUTE) == 0 && hour != lastHour) {
                lastHour = hour
                speakClock()
            }
        }
    }

    fun startup(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        active = Settings.tamiRadio(ctx)
        lastHour = -1
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, POLL_MS)
    }

    fun bind(callback: (() -> Unit)?) {
        onChange = callback
    }

    fun start(context: Context) {
        if (active) return
        val ctx = context.applicationContext
        app = ctx
        ThreadPool.post {
            val songs = Library.allSongs(ctx)
            if (songs.isEmpty()) {
                ThreadPool.onUi { speak(ctx.getString(R.string.tami_radio_need)) }
                return@post
            }
            val queue = songs.shuffled()
            ThreadPool.onUi {
                Telemetry.log(ctx, "TamiRadio start n=${queue.size}")
                Playback.setShuffle(true)
                Playback.setRepeatAll(true)
                Playback.setSleepMix(false)
                Playback.start(queue, 0)
                lastHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                active = true
                Settings.setTamiRadio(ctx, true)
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, POLL_MS)
                speak(ctx.getString(R.string.tami_radio_welcome, queue.size))
                onChange?.invoke()
            }
        }
    }

    fun stop(context: Context) {
        if (!active) return
        val ctx = context.applicationContext
        active = false
        Settings.setTamiRadio(ctx, false)
        handler.removeCallbacks(tick)
        speak(ctx.getString(R.string.tami_radio_off))
        onChange?.invoke()
    }

    private fun speakClock() {
        val ctx = app ?: return
        if (!active || !Settings.djVoice(ctx)) return
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val (greetRes, startRes) = when {
            hour < 12 -> R.string.tami_clock_greet_day to R.string.tami_clock_start_day
            hour < 18 -> R.string.tami_clock_greet_after to R.string.tami_clock_start_after
            else -> R.string.tami_clock_greet_night to R.string.tami_clock_start_night
        }
        val time = if (minute == 0) {
            ctx.getString(R.string.tami_clock_time_sharp, hour)
        } else {
            ctx.getString(R.string.tami_clock_time, hour, minute)
        }
        val song = Playback.currentSong
        val track = if (song != null) {
            ctx.getString(R.string.dj_voice_track, song.artist, song.title)
        } else {
            ""
        }
        speak(
            listOf(ctx.getString(greetRes), time, ctx.getString(startRes), track)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        )
    }

    private fun speak(text: String) {
        val ctx = app ?: return
        if (text.isBlank() || !Settings.djVoice(ctx)) return
        voice ?: DjVoice(ctx, Settings.languageTag(Settings.language(ctx))).also { voice = it }
        voice!!.init { ready -> if (ready) voice!!.speak(text) }
    }
}