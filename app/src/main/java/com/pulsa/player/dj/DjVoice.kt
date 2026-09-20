package com.pulsa.player.dj
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.pulsa.player.R
import com.pulsa.player.core.Settings
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
        private val chosenVoices = HashMap<String, Voice>()
    }

    fun currentName(): String {
        val male = Settings.masculineAvatar(appContext)
        return appContext.getString(if (male) R.string.dj_voice_name_male else R.string.dj_voice_name)
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
                    tts?.setPitch(pitch())
                    applyVoice()
                }
            }
            onReady(ready)
        }
    }

    private fun pitch(): Float =
        if (Settings.masculineAvatar(appContext)) 0.82f else 1.25f

    private fun applyVoice() {
        val voice = resolveVoice()
        if (voice != null) {
            runCatching { tts?.setVoice(voice) }
        } else {
            runCatching { tts?.language = targetLocale }
        }
    }

    private fun resolveVoice(): Voice? {
        val male = Settings.masculineAvatar(appContext)
        chosenVoices["${targetLocale}|$male"]?.let { return it }
        val voices = tts?.voices ?: return null
        val femaleTokens = listOf(
            "female", "feminina", "femenina", "femenine", "woman", "mulher", "voz feminina"
        )
        val maleTokens = listOf(
            "male", "masculina", "masculino", "femenino", "man", "homem", "uno", "voz masculina", "en-male"
        )

        fun genderFirst(list: List<Voice>): Voice? {
            val sorted = list.sortedBy { it.name ?: "" }
            val tokens = if (male) maleTokens else femaleTokens
            val wanted = sorted.firstOrNull { v ->
                v.name?.let { n -> tokens.any { t -> n.contains(t, ignoreCase = true) } } == true
            }
            if (wanted != null) return wanted
            // Caminho alternativo: Google nomeia as vozes por apelido ("pt-br-x-iap") —
            // sem marcador de gênero, aceita qualquer voz que não contenha o outro gênero.
            val other = if (male) femaleTokens else maleTokens
            return sorted.firstOrNull { v ->
                v.name?.let { n -> other.none { t -> n.contains(t, ignoreCase = true) } } == true
            } ?: sorted.firstOrNull()
        }

        // Mesmo idioma do app primeiro (correto), preferindo a voz do gênero do avatar.
        // Nunca cai para outro idioma, para a voz ficar sempre no idioma da interface.
        val sameLang = voices.filter { runCatching { it.locale.language == targetLocale.language }.getOrDefault(false) }
        val chosen = genderFirst(sameLang) ?: sameLang.firstOrNull()
        if (chosen != null) chosenVoices["${targetLocale}|$male"] = chosen
        return chosen
    }

    private fun pronounce(text: String): String {
        val lang = targetLocale.language.lowercase()
        val dj = if (lang == "en") "Dee Jay" else "djei"
        var out = text.replace(Regex("(?i)\\bDJ\\b"), dj)
        if (Settings.masculineAvatar(appContext)) {
            out = out.replace(Regex("(?i)\\bVirgin\\b"), currentName())
        }
        return out
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
            tts?.setPitch(pitch())
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
            val status = tts?.speak(pronounce(text), TextToSpeech.QUEUE_FLUSH, null, "dj_virgin") ?: TextToSpeech.ERROR
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
