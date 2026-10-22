package com.pulsa.player.wear

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.TextView
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONObject
import java.util.Locale

/**
 * Controle remoto do Pulsa no pulso.
 *
 * Não toca áudio no relógio: manda intenções ao celular pelo Data Layer e desenha o estado
 * que o celular devolve. O polling de 2 s só existe enquanto a tela está na frente
 * (`onResume`/`onPause`), porque no pulso a tela apaga sozinha em segundos — um listener
 * permanente gastaria bateria dos dois lados sem ninguém olhando.
 *
 * Os caminhos e as chaves JSON estão repetidos no `WearBridgeService` do celular. São dois
 * APKs sem código compartilhado por decisão: o módulo `:wear` não pode depender de `:app`
 * (o `:app` é quem embute o `:wear`), e um terceiro módulo só para quatro strings custaria
 * mais do que o comentário que avisa para manter os dois lados iguais.
 */
class WearActivity : Activity() {

    companion object {
        private const val CMD_PATH = "/pulsa/cmd"
        private const val STATE_PATH = "/pulsa/state"
        private const val REQ_SPEECH = 41
        private const val POLL_MS = 2_000L
    }

    private lateinit var titleView: TextView
    private lateinit var artistView: TextView
    private lateinit var positionView: TextView
    private lateinit var toggleButton: Button
    private lateinit var favoriteButton: Button
    private lateinit var statusView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var nodeId: String? = null
    private var resolvingNode = false

    private val stateListener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path != STATE_PATH) return@OnMessageReceivedListener
        val json = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull() ?: return@OnMessageReceivedListener
        runOnUiThread { render(json) }
    }

    private val poll = object : Runnable {
        override fun run() {
            requestState()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear)
        titleView = findViewById(R.id.wear_title)
        artistView = findViewById(R.id.wear_artist)
        positionView = findViewById(R.id.wear_position)
        toggleButton = findViewById(R.id.wear_toggle)
        favoriteButton = findViewById(R.id.wear_favorite)
        statusView = findViewById(R.id.wear_status)

        findViewById<Button>(R.id.wear_prev).setOnClickListener { send("prev") }
        toggleButton.setOnClickListener { send("toggle") }
        findViewById<Button>(R.id.wear_next).setOnClickListener { send("next") }
        favoriteButton.setOnClickListener { send("fav") }
        findViewById<Button>(R.id.wear_speak).setOnClickListener { speak() }

        toggleButton.text = getString(R.string.wear_play)
        favoriteButton.text = getString(R.string.wear_favorite)
    }

    override fun onResume() {
        super.onResume()
        runCatching { Wearable.getMessageClient(this).addListener(stateListener) }
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
        runCatching { Wearable.getMessageClient(this).removeListener(stateListener) }
    }

    // -------------------------------------------------------------------- envio

    private fun send(command: String) {
        val node = nodeId
        if (node == null) {
            setStatus(R.string.wear_connecting)
            resolveNode { connected -> sendTo(connected, command) }
            return
        }
        sendTo(node, command)
    }

    private fun requestState() {
        val node = nodeId
        if (node == null) {
            resolveNode { connected -> sendTo(connected, "state") }
            return
        }
        sendTo(node, "state")
    }

    private fun sendTo(node: String, command: String) {
        runCatching {
            Wearable.getMessageClient(this)
                .sendMessage(node, CMD_PATH, command.toByteArray())
                .addOnFailureListener { runOnUiThread { setStatus(R.string.wear_error) } }
        }.onFailure { setStatus(R.string.wear_error) }
    }

    /**
     * Descobre o nó do celular par. `getConnectedNodes` é assíncrono e pode voltar vazio
     * quando o Bluetooth acabou de ligar; por isso o resultado é guardado e a próxima ação
     * tenta de novo. A trava `resolvingNode` evita uma consulta por tick do polling.
     */
    private fun resolveNode(after: (String) -> Unit) {
        if (resolvingNode) return
        resolvingNode = true
        runCatching {
            Wearable.getNodeClient(this).connectedNodes
                .addOnSuccessListener { nodes ->
                    resolvingNode = false
                    runOnUiThread {
                        val node = nodes.firstOrNull()
                        if (node == null) {
                            setStatus(R.string.wear_no_phone)
                        } else {
                            nodeId = node.id
                            after(node.id)
                        }
                    }
                }
                .addOnFailureListener {
                    resolvingNode = false
                    runOnUiThread { setStatus(R.string.wear_error) }
                }
        }.onFailure {
            resolvingNode = false
            setStatus(R.string.wear_error)
        }
    }

    // ------------------------------------------------------------------- estado

    private fun render(raw: String) {
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: run {
            setStatus(R.string.wear_invalid)
            return
        }
        val ready = o.optBoolean("ready", false)
        val playing = o.optBoolean("playing", false)
        val title = o.optString("title")
        val artist = o.optString("artist")
        val position = o.optLong("pos", 0L)
        val duration = o.optLong("dur", 0L)
        val favorite = o.optInt("fav", -1)
        val msg = o.optString("msg")
        val arg = o.optString("arg")

        titleView.text = if (ready && title.isNotEmpty()) title else getString(R.string.wear_idle)
        artistView.text = if (ready) artist else ""
        positionView.text = if (ready && duration > 0L) {
            "${formatTime(position)} / ${formatTime(duration)}"
        } else {
            ""
        }
        toggleButton.text = getString(if (playing) R.string.wear_pause else R.string.wear_play)
        favoriteButton.isEnabled = favorite >= 0
        favoriteButton.text = getString(
            when (favorite) {
                1 -> R.string.wear_favorited
                0 -> R.string.wear_favorite
                else -> R.string.wear_favorite
            }
        )
        statusView.text = when (msg) {
            "not_found" -> getString(R.string.wear_not_found, arg)
            "no_permission" -> getString(R.string.wear_no_permission)
            "no_queue" -> getString(R.string.wear_no_queue)
            "idle" -> getString(R.string.wear_idle_hint)
            "error" -> getString(R.string.wear_error)
            else -> if (ready) getString(R.string.wear_connected) else getString(R.string.wear_idle_hint)
        }
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L).toInt()
        val minutes = total / 60
        val seconds = total % 60
        return "%d:%02d".format(Locale.US, minutes, seconds)
    }

    private fun setStatus(res: Int, vararg args: Any) {
        statusView.text = getString(res, *args)
    }

    // --------------------------------------------------------------------- voz

    /**
     * "Tocar X" usa o reconhecedor do próprio relógio, sem abrir a tela do celular.
     * Se o aparelho não tiver o app de voz (ou ele não estiver instalado), o botão avisa
     * em vez de sumir — esconder a ação deixaria o pulso sem o único jeito de escolher
     * uma faixa específica.
     */
    private fun speak() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.wear_speak_prompt))
        try {
            startActivityForResult(intent, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            setStatus(R.string.wear_speech_unavailable)
        }
    }

    @Deprecated("O reconhecedor do relógio só expõe o resultado por startActivityForResult.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH || resultCode != RESULT_OK) return
        val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return
        statusView.text = getString(R.string.wear_searching, text)
        send("play:$text")
    }
}
