package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.core.Settings

/**
 * A voz da Virgin para o "ganhando voz": avisos curtos fora do [DjSession], como o passo
 * de velocidade novo. TTS é caro de criar, então reusa uma única instância de [DjVoice]
 * (mesmo truque do `bgVoice` do PlaybackService); se ela ainda não estiver pronta, o aviso
 * cai fora — melhor do que travar a UI esperando a engine subir.
 */
object DjAnnounce {
    @Volatile
    private var voice: DjVoice? = null

    fun speak(context: Context, text: String) {
        val v = voice ?: DjVoice(
            context,
            Settings.languageTag(Settings.language(context))
        ).also { voice = it }
        v.init { ready ->
            if (ready) v.speak(text) else v.shutdown()
        }
    }
}