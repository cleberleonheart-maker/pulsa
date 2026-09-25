package com.pulsa.player.dj
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

class DjCommandListener(
    context: Context,
    private val onResult: (text: String) -> Unit
) {
    private val appContext = context.applicationContext
    private val locale: Locale = Locale.getDefault()
    private val recognizer: SpeechRecognizer? =
        if (SpeechRecognizer.isRecognitionAvailable(appContext))
            SpeechRecognizer.createSpeechRecognizer(appContext)
        else null

    @Volatile
    private var listening = false

    @Volatile
    private var reportedUnsupported = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartMs = 0L
    private val minGapMs = 3000L
    private var retryMs = FIRST_RETRY_MS
    private val retryRunnable = Runnable { restart() }

    @Volatile
    private var emptyCount = 0

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!listening) return
                when (error) {
                    // Erros transitorios: volta a escutar com espera crescente.
                    // Sem isso o microfone abria e fechava a cada ~3s enquanto a musica
                    // tocava, sem parar: cada startListening() liga o microfone de novo,
                    // e o usuario via o indicador piscando sem parar.
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        emptyCount++
                        mainHandler.removeCallbacks(retryRunnable)
                        mainHandler.postDelayed(retryRunnable, retryMs)
                        retryMs = (retryMs * 2).coerceAtMost(MAX_RETRY_MS)
                    }
                    // Erros fatais: para de ouvir e avisa a UI uma unica vez
                    else -> {
                        listening = false
                        mainHandler.removeCallbacks(retryRunnable)
                        mainHandler.post {
                            if (!reportedUnsupported) {
                                reportedUnsupported = true
                                onResult("__unsupported__")
                            }
                        }
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    ?: ""
                if (text.isNotEmpty()) {
                    emptyCount = 0
                    retryMs = FIRST_RETRY_MS
                    onResult(text)
                } else {
                    emptyCount++
                }
                if (listening) {
                    ThreadPool.onUi { restart() }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    fun start() {
        if (recognizer == null) {
            onResult("__unsupported__")
            return
        }
        reportedUnsupported = false
        listening = true
        emptyCount = 0
        retryMs = FIRST_RETRY_MS
        startListening()
    }

    fun stop() {
        listening = false
        emptyCount = 0
        retryMs = FIRST_RETRY_MS
        mainHandler.removeCallbacks(retryRunnable)
        runCatching { recognizer?.cancel() }
    }

    fun destroy() {
        listening = false
        mainHandler.removeCallbacks(retryRunnable)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
    }

    private fun startListening() {
        if (!listening) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartMs < minGapMs) {
            val wait = minGapMs - (now - lastStartMs)
            mainHandler.postDelayed({ startListening() }, wait)
            return
        }
        lastStartMs = now
        runCatching { recognizer?.startListening(intent()) }
    }

    private fun restart() {
        startListening()
    }

    private fun intent(): Intent {
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Janela curta de silencio: sem isso o reconhecedor segura o microfone
            // aberto pelo tempo padrao mesmo depois de voce terminar de falar.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        }
    }

    companion object {
        /** Espera antes de reabrir o microfone quando ninguem falou nada. */
        private const val FIRST_RETRY_MS = 2000L
        private const val MAX_RETRY_MS = 15000L
    }
}
