package com.pulsa.player.dj
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

class DjVoice(context: Context, languageTag: String? = null) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    var ready = false
        private set

    @Volatile
    private var pending: (() -> Unit)? = null

    @Volatile
    var isSpeaking: Boolean = false
        private set

    private val targetLocale: Locale =
        if (!languageTag.isNullOrBlank()) {
            runCatching { Locale.forLanguageTag(languageTag) }.getOrNull() ?: Locale.getDefault()
        } else {
            Locale.getDefault()
        }

    companion object {
        private val chosenVoices = HashMap<Locale, Voice>()
    }

    fun init(onReady: (Boolean) -> Unit) {
        if (tts != null) {
            onReady(ready)
            return
        }
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching {
                    tts?.setSpeechRate(0.9f)
                    tts?.setPitch(1.25f)
                    applyVoice()
                }
            }
            onReady(ready)
        }
    }

    private fun applyVoice() {
        val voice = resolveVoice()
        if (voice != null) {
            runCatching { tts?.setVoice(voice) }
        } else {
            runCatching { tts?.language = targetLocale }
        }
    }

    private fun resolveVoice(): Voice? {
        chosenVoices[targetLocale]?.let { return it }
        val voices = tts?.voices ?: return null
        val femaleTokens = listOf(
            "female", "feminina", "femenina", "femenine", "woman", "mulher", "voz feminina"
        )

        fun femaleFirst(list: List<Voice>): Voice? {
            val sorted = list.sortedBy { it.name ?: "" }
            return sorted.firstOrNull { v ->
                v.name?.let { n -> femaleTokens.any { t -> n.contains(t, ignoreCase = true) } } == true
            } ?: sorted.firstOrNull()
        }

        // Mesmo idioma do app primeiro (correto), preferindo voz feminina.
        // Nunca cai para outro idioma, para a voz ficar sempre no idioma da interface.
        val sameLang = voices.filter { runCatching { it.locale.language == targetLocale.language }.getOrDefault(false) }
        val chosen = femaleFirst(sameLang) ?: sameLang.firstOrNull()
        if (chosen != null) chosenVoices[targetLocale] = chosen
        return chosen
    }

    fun speak(text: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) {
            ThreadPool.onUi { onDone?.invoke() }
            return
        }
        val previous = pending
        if (previous != null) {
            pending = null
            ThreadPool.onUi { previous() }
        }
        isSpeaking = true
        runCatching {
            tts?.setSpeechRate(0.9f)
            tts?.setPitch(1.25f)
            val voice = resolveVoice()
            if (voice != null) {
                tts?.setVoice(voice)
                tts?.language = voice.locale
            } else {
                tts?.language = targetLocale
            }
            var fired = false
            fun fire() {
                if (fired) return
                fired = true
                isSpeaking = false
                val cb = pending
                pending = null
                if (cb != null) ThreadPool.onUi { cb() }
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = fire()
                override fun onError(utteranceId: String?) = fire()
            })
            pending = onDone
            val status = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dj_virgin") ?: TextToSpeech.ERROR
            if (status == TextToSpeech.ERROR) {
                isSpeaking = false
                fire()
            }
        }
    }

    fun stop() {
        isSpeaking = false
        runCatching { tts?.stop() }
        val cb = pending
        pending = null
        if (cb != null) ThreadPool.onUi { cb() }
    }

    fun shutdown() {
        isSpeaking = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        val cb = pending
        pending = null
        if (cb != null) ThreadPool.onUi { cb() }
        tts = null
        ready = false
    }
}
