package com.pulsa.player.ui

import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import com.pulsa.player.core.ThreadPool
import java.util.Locale

/**
 * Diagnóstico da voz offline, para o Perfil responder à pergunta que não dá para adivinhar:
 * o pacote de reconhecimento e a voz local estão realmente instalados neste aparelho?
 *
 * O TTS é assíncrono, então a resposta vem por callback. A instância é temporária e some no
 * fim — nada de segurar um engine só para mostrar um diálogo.
 */
object VoiceDiagnostics {

    data class Report(
        val recognitionAvailable: Boolean,
        val onDeviceAvailable: Boolean,
        val androidTooOld: Boolean,
        val languageAvailable: Boolean,
        val localVoices: Int,
        val networkVoices: Int,
        val languageLabel: String
    )

    fun inspect(context: Context, languageTag: String, onResult: (Report) -> Unit) {
        val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrNull()
            ?: Locale.getDefault()
        val recognition = runCatching {
            SpeechRecognizer.isRecognitionAvailable(context)
        }.getOrDefault(false)
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && runCatching {
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        }.getOrDefault(false)

        var engine: TextToSpeech? = null
        runCatching {
            engine = TextToSpeech(context.applicationContext) { status ->
                val tts = engine
                val voices = if (status == TextToSpeech.SUCCESS) {
                    runCatching { tts?.voices }.getOrNull()
                } else {
                    null
                }
                val sameLanguage = voices?.filter { it.locale.language == locale.language }.orEmpty()
                val local = sameLanguage.count { !it.isNetworkConnectionRequired }
                val network = sameLanguage.count { it.isNetworkConnectionRequired }
                val languageAvailable = status == TextToSpeech.SUCCESS && (
                    runCatching { tts?.isLanguageAvailable(locale) }.getOrNull()
                        ?: TextToSpeech.LANG_NOT_SUPPORTED
                    ) >= TextToSpeech.LANG_AVAILABLE
                runCatching { tts?.shutdown() }
                val report = Report(
                    recognitionAvailable = recognition,
                    onDeviceAvailable = onDevice,
                    androidTooOld = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                    languageAvailable = languageAvailable,
                    localVoices = local,
                    networkVoices = network,
                    languageLabel = locale.getDisplayLanguage(locale)
                        .replaceFirstChar { it.uppercase(locale) }
                )
                ThreadPool.onUi { onResult(report) }
            }
        }.onFailure {
            onResult(
                Report(
                    recognitionAvailable = recognition,
                    onDeviceAvailable = onDevice,
                    androidTooOld = Build.VERSION.SDK_INT < Build.VERSION_CODES.S,
                    languageAvailable = false,
                    localVoices = 0,
                    networkVoices = 0,
                    languageLabel = locale.getDisplayLanguage(locale)
                        .replaceFirstChar { c -> c.uppercase(locale) }
                )
            )
        }
    }
}
