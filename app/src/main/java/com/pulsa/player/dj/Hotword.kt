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
        // Vídeo pausado conta como "toca". Pausar o filme é justamente o estado em que
        // se diz "continua o filme", e a regra antiga recusava o mãos-livres aí — o que
        // contradizia o `publishState`, que já não desliga o microfone quando o que para
        // é vídeo. As duas juntas davam: app aberto com vídeo pausado mantinha o
        // microfone, e fechar o app (que passa por `stopForBackground`) o tirava.
        val song = Playback.currentSong
        val videoish = song?.isVideo == true || song?.isStream == true
        if (!Playback.isPlaying && !videoish) return
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