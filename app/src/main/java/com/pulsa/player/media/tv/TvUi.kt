package com.pulsa.player.media.tv

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool

/**
 * A UI do "Tocar na TV", compartilhada entre a home e a tela de reprodução.
 *
 * A lógica (descobrir, conectar, painel de controle, diagnóstico) vivia só no
 * [com.pulsa.player.ui.NowPlayingFragment]; a home ganhou o mesmo botão e duplicar isso
 * significaria dois lugares para consertar quando o fluxo do Roku mudar. Aqui recebe o
 * [Fragment] dono da tela — cada chamada confere se ele ainda está adicionado antes de
 * mexer em diálogo/Toast, porque a descoberta roda em background e a tela pode ter saído.
 */
object TvUi {

    /**
     * Ação do botão TV: conectado abre o painel; idle reconecta à última TV ou descobre.
     * Idêntico nas duas telas — é o mesmo botão em contextos diferentes.
     */
    fun onClicked(fragment: Fragment) {
        if (!fragment.isAdded) return
        when (val s = TvLink.state) {
            is TvLink.State.Busy ->
                toastText(fragment, s.label)
            is TvLink.State.Connected -> {
                if (!s.playerAberto) {
                    // TV em uso: o app não trocou nada — explicar em vez do aviso genérico.
                    val msg = if (TvLink.tvEmUso) R.string.tv_busy else R.string.tv_player_manual
                    toastLong(fragment, msg)
                }
                showControls(fragment, s.device)
            }
            is TvLink.State.Idle -> {
                // TV já pareada antes: um toque reconecta (sem esperar descoberta).
                if (TvLink.reconnect(fragment.requireContext().applicationContext)) {
                    toast(fragment, R.string.tv_searching)
                } else {
                    discover(fragment)
                }
            }
        }
    }

    /** Descobre Rokus na rede (SSDP + varredura) e abre a lista para o usuário escolher. */
    private fun discover(fragment: Fragment) {
        if (!fragment.isAdded) return
        val app = fragment.requireContext().applicationContext
        toast(fragment, R.string.tv_searching)
        ThreadPool.postNetwork {
            val devices = runCatching { RokuEcp.discover() }.getOrDefault(emptyList())
            ThreadPool.onUi {
                if (!fragment.isAdded) return@onUi
                if (devices.isEmpty()) {
                    toastLong(fragment, R.string.tv_none)
                    return@onUi
                }
                val names = devices.map { it.friendlyName }.toTypedArray()
                MaterialAlertDialogBuilder(fragment.requireContext())
                    .setTitle(R.string.tv_cast)
                    .setSingleChoiceItems(names, -1) { d, which ->
                        d.dismiss()
                        if (!fragment.isAdded) return@setSingleChoiceItems
                        TvLink.connect(app, devices[which])
                        Toast.makeText(
                            fragment.requireContext(),
                            fragment.getString(R.string.tv_connecting, devices[which].friendlyName),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    /**
     * Painel de controle da TV pareada. Play/pause/±/volume são keypress ECP — o app não
     * tem a fila da TV (o Roku Media Player a gerencia), então o que o app manda é
     * exatamente o que o controle remoto mandaria.
     */
    fun showControls(fragment: Fragment, device: RokuEcp.Device) {
        if (!fragment.isAdded) return
        val options = arrayOf(
            fragment.getString(R.string.tv_pause),
            fragment.getString(R.string.tv_forward),
            fragment.getString(R.string.tv_rewind),
            fragment.getString(R.string.tv_vol_up),
            fragment.getString(R.string.tv_vol_down),
            fragment.getString(R.string.tv_open_player),
            fragment.getString(R.string.tv_diag),
            fragment.getString(R.string.tv_disconnect),
        )
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(fragment.getString(R.string.tv_controls, device.friendlyName))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> TvLink.keypress("Play")
                    1 -> TvLink.keypress("Forward")
                    2 -> TvLink.keypress("Rewind")
                    3 -> TvLink.keypress("VolumeUp")
                    4 -> TvLink.keypress("VolumeDown")
                    5 -> TvLink.relaunch { abriu ->
                        if (!fragment.isAdded) return@relaunch
                        if (abriu) {
                            toast(fragment, R.string.tv_player_opened)
                        } else {
                            showLaunchFailed(fragment)
                        }
                    }
                    6 -> showDiagnostico(fragment)
                    7 -> {
                        TvLink.disconnect()
                        toast(fragment, R.string.tv_disconnect)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Falha do launch com o rastro (códigos HTTP/active-app). Sem este diálogo o botão
     * "Abrir Roku Media Player" falhava em silêncio e o diagnóstico tinha que virar
     * palpite — com ele o usuário manda a foto e o caminho já aparece escrito.
     */
    private fun showLaunchFailed(fragment: Fragment) {
        if (!fragment.isAdded) return
        val log = TvLink.ultimoLaunch
        val msg = log.joinToString("\n") + "\n\n" + fragment.getString(R.string.tv_player_manual)
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.tv_player_failed)
            .setMessage(msg)
            .setNeutralButton(R.string.tv_copy) { _, _ -> copiar(fragment, msg) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** O que o servidor DLNA viu chegar da TV + último launch — separa "rede" de "resposta". */
    private fun showDiagnostico(fragment: Fragment) {
        if (!fragment.isAdded) return
        val texto = TvLink.diagnostico()
        MaterialAlertDialogBuilder(fragment.requireContext())
            .setTitle(R.string.tv_diag)
            .setMessage(texto)
            .setNeutralButton(R.string.tv_copy) { _, _ -> copiar(fragment, texto) }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Copiar o texto em vez de print: no celular o print de diálogo longo vira uma foto
     * ilegível — texto colado no zap/Telegram o lê e o manda direto pra gente.
     */
    private fun copiar(fragment: Fragment, texto: String) {
        if (!fragment.isAdded) return
        fragment.requireContext().getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText("Pulsa", texto))
        toast(fragment, R.string.tv_copied)
    }

    private fun toast(fragment: Fragment, res: Int) {
        if (!fragment.isAdded) return
        Toast.makeText(fragment.requireContext(), res, Toast.LENGTH_SHORT).show()
    }

    private fun toastText(fragment: Fragment, texto: CharSequence) {
        if (!fragment.isAdded) return
        Toast.makeText(fragment.requireContext(), texto, Toast.LENGTH_SHORT).show()
    }

    private fun toastLong(fragment: Fragment, res: Int) {
        if (!fragment.isAdded) return
        Toast.makeText(fragment.requireContext(), res, Toast.LENGTH_LONG).show()
    }
}
