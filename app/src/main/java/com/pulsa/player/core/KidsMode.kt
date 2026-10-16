package com.pulsa.player.core

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AndroidSettings

/**
 * Modo família/kids (item 293) — a parte que fala com o Android.
 *
 * Aqui mora o que precisa de `Context`: travar o volume do stream de música e guardar/checar a
 * senha (hash). A triagem de conteúdo é pura e vive em [KidsFilter].
 */
object KidsMode {

    /** Teto do volume de música no modo kids, em porcentagem do máximo do aparelho. */
    const val MAX_PERCENT = 70

    /** SHA-256 em hexadecimal — não guardamos a senha em texto puro nas preferências. */
    fun hash(pin: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(pin.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun matches(pin: String, storedHash: String): Boolean =
        storedHash.isNotBlank() && hash(pin) == storedHash

    private fun audio(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Índice máximo permitido de `STREAM_MUSIC` (mínimo 1, para nunca zerar o áudio). */
    fun maxStreamIndex(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return (max * MAX_PERCENT / 100).coerceAtLeast(1)
    }

    /** Baixa o volume para o teto quando ele passou — sem mostrar a UI de volume. */
    fun clampVolume(context: Context) {
        val am = audio(context) ?: return
        runCatching {
            val cap = maxStreamIndex(am)
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > cap) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, cap, 0)
            }
        }
    }

    @Volatile
    private var observer: ContentObserver? = null

    /**
     * Passa a vigiar o volume do sistema: toda vez que ele mudar **com o modo kids ligado**,
     * o volume é rebaixado para o teto. O observador fica registrado a vida toda do processo
     * (é barato) e só age quando o modo está ligado; assim vale também quando o modo é ligado
     * depois, sem precisar religar o vigia.
     */
    fun watchVolume(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val obs = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (Settings.kidsMode(app)) clampVolume(app)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                AndroidSettings.System.CONTENT_URI, true, obs
            )
            observer = obs
        }
    }
}
