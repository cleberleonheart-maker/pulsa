package com.pulsa.player.dj

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.pulsa.player.core.Settings
import com.pulsa.player.playback.Playback

/**
 * Controla o listener de mãos-livres: mantém o HotwordService rodando
 * enquanto a música toca e o toggle estiver ligado, e garante coexistência
 * com o microfone da Virgínia em primeiro plano (nunca dois listeners juntos).
 */
object Hotword {

    @Volatile
    var running = false

    fun startIfNeeded(context: Context) {
        val ctx = context.applicationContext
        if (running) return
        if (!Settings.hotword(ctx)) return
        if (!Playback.isPlaying) return
        val micOk = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!micOk) return
        // Virgínia com microfone ativo já escuta; não duplicar o reconhecedor.
        if (HotwordBridge.virginActive()) return
        runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, HotwordService::class.java))
        }
    }

    fun stopIfRunning(context: Context) {
        if (!running) return
        val ctx = context.applicationContext
        runCatching { ctx.stopService(Intent(ctx, HotwordService::class.java)) }
        running = false
    }
}