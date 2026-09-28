package com.pulsa.player.dj

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

/**
 * Como o microfone se comporta depois de uma janela de escuta.
 *
 * A versao antiga abria o microfone num laço infinito enquanto a maos-livres estivesse ligada
 * — o usuario viu isso tres vezes seguindo numeros (3s -> 2/4/8/15s), nao a estrutura. A
 * conclusao honesta e que nao existe escuta de palavra-chave silenciosa aqui: qualquer app
 * que "escuta a palavra" abre o microfone e o indicador do sistema acende. Entao a maos-livres
 * virou UM_SO disparo: o microfone so abre quando voce pede, por 6s, e fecha sozinho.
 */
enum class MicMode {
    /**
     * Quem apertou o botao de microfone esta falando agora: reabre em seguida para nao
     * perder o proximo comando da conversa. E o caso da tela do DJ.
     */
    CONTINUOUS,

    /**
     * Uma unica janela de escuta, pelo tempo que o chamador passa em `start()`, e depois
     * fecha sozinha. Nao reabre em hipotese alguma. E o caso do botao "Ouvir" da notificacao
     * de maos-livres e da escuta da Virgin na tela — o indicador de microfone so acende
     * quando o usuario pediu, e apaga quando a janela acaba.
     */
    ONE_SHOT
}

class DjCommandListener(
    context: Context,
    private val mode: MicMode = MicMode.CONTINUOUS,
    private val onResult: (text: String) -> Unit,
    private val onClosed: (() -> Unit)? = null
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

    /** Nesta chamada o microfone está em janela [start] e deve aguentar erros transientes. */
    private var windowed = false

    /** Backoff interno para retentar durante a janela sem resetar o reconhecedor. */
    private var windowRetryMs = 500L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastStartMs = 0L
    private val minGapMs = 3000L
    private val cycle = MicCycle(mode, MicBackoff(FIRST_RETRY_MS, MAX_RETRY_MS))
    private val retryRunnable = Runnable { restart() }

    /** Fecha sozinho quando o tempo da janela de [ONE_SHOT] acaba. */
    private val windowRunnable = Runnable { stop() }
    private var closedCallbackRun = false

    private fun runClosed() {
        if (closedCallbackRun) return
        closedCallbackRun = true
        onClosed?.invoke()
    }

    init {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                windowRetryMs = 500L
            }
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
                        // Janela ativa (escuta por pedido): erro transiente nao pode fechar a
                        // janela na hora — e o "tenho que apertar de novo". Com musica tocando o
                        // reconhecedor devolve BUSY/NO_MATCH no meio da janela ou logo ao reabrir
                        // depois da resposta da Virgin; retenta dentro do tempo da janela com
                        // backoff ate o reconhecedor sossegar. O fim da janela (windowRunnable)
                        // e quem realmente fecha.
                        if (windowed && mainHandler.hasCallbacks(windowRunnable)) {
                            mainHandler.removeCallbacks(retryRunnable)
                            mainHandler.postDelayed(retryRunnable, windowRetryMs)
                            windowRetryMs = (windowRetryMs * 2).coerceAtMost(MAX_WINDOW_RETRY_MS)
                        } else {
                            scheduleNext(heard = false)
                        }
                    }
                    // Erros fatais: para de ouvir e avisa a UI uma unica vez, e fecha a janela
                    // (senao o chamador ficaria com windowOpen/duck presos para sempre).
                    else -> {
                        listening = false
                        mainHandler.removeCallbacks(retryRunnable)
                        mainHandler.removeCallbacks(windowRunnable)
                        mainHandler.post {
                            if (!reportedUnsupported) {
                                reportedUnsupported = true
                                onResult("__unsupported__")
                            }
                            runClosed()
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
                    cycle.reset()
                    onResult(text)
                }
                // Ouviu ou nao ouviu, a proxima janela e a politica que decide — inclusive
                // o caso vazio, que e o comum com musica tocando (e era por via dele que o
                // ciclo de ~3s voltava).
                scheduleNext(heard = text.isNotEmpty())
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    /** Abre o microfone de novo no tempo que a politica [MicCycle] mandar. Para [ONE_SHOT],
     * a politica devolve "nunca mais" e a janela fecha sozinha. */
    private fun scheduleNext(heard: Boolean) {
        if (!listening) return
        mainHandler.removeCallbacks(retryRunnable)
        val wait = cycle.waitBeforeReopen(heard)
        if (wait == null) {
            stop()
            return
        }
        if (wait <= 0L) restart() else mainHandler.postDelayed(retryRunnable, wait)
    }

    /**
     * Abre uma janela de escuta. [windowMs] > 0 faz o microfone fechar sozinho ao fim de
     * [windowMs] — e o que a maos-livres e a Virgin usam para "so quando eu pedir".
     */
    fun start(windowMs: Long = 0L) {
        if (recognizer == null) {
            onResult("__unsupported__")
            runClosed()
            return
        }
        reportedUnsupported = false
        listening = true
        cycle.reset()
        windowRetryMs = 500L
        windowed = windowMs > 0
        if (windowed) {
            mainHandler.removeCallbacks(windowRunnable)
            mainHandler.postDelayed(windowRunnable, windowMs)
        }
        startListening()
    }

    fun stop() {
        val wasListening = listening
        listening = false
        cycle.reset()
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(windowRunnable)
        runCatching { recognizer?.cancel() }
        if (wasListening) runClosed()
    }

    fun destroy() {
        val wasListening = listening
        listening = false
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.removeCallbacks(windowRunnable)
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        if (wasListening) runClosed()
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

        /** Teto do backoff do retry dentro de uma janela de escuta por pedido. */
        private const val MAX_WINDOW_RETRY_MS = 3000L
    }
}
