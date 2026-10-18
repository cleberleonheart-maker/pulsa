package com.pulsa.player.media.tv

import android.content.Context
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.Library
import com.pulsa.player.data.VideoLibrary
import java.io.File
import java.util.UUID

/**
 * A3 — orquestra a sessão "tocar na TV": descobre o Roku, sobe o [DlnaServer] com a
 * biblioteca, lança o Roku Media Player e expõe o estado para a UI.
 *
 * **Formato honesto (v1).** O Roku não permite deep-link para item DLNA, então o fluxo
 * real é: app sobe servidor → ECP lança Roku Media Player → usuário escolhe o servidor
 * "Pulsa" no controle da TV → play/pause/±/volume pelo app via keypress. Fila e navegação
 * na TV são do próprio Roku Media Player; espelhar a fila do app aqui seria uma segunda
 * camada de UI que a TV não pediu.
 *
 * **Thread.** Toda a montagem (MediaStore + Room não entra aqui, só MediaStore) roda em
 * [ThreadPool.postNetwork] — montar a árvore de mil faixas e ligar socket não pode segurar
 * a UI. Estado volta para a UI só via [ThreadPool.onUi].
 */
object TvLink {

    /** UUID estável do servidor DLNA desta instalação — a TV não pode ver "Pulsa" novo a cada sessão. */
    private var serverUuid: String? = null

    sealed class State {
        /** Sem sessão — botão da TV desligado. */
        object Idle : State()

        /** Procurando/conectando — botão mostra progresso e ignorar toque. */
        data class Busy(val label: String) : State()

        /** Sessão ativa: servidor no ar, TV pareada. */
        data class Connected(val device: RokuEcp.Device) : State()
    }

    @Volatile
    var state: State = State.Idle
        private set

    private var server: DlnaServer? = null

    /** Listeners de mudança de estado (UI registra em onResume, limpa em onPause). */
    private val listeners = ArrayList<(State) -> Unit>()

    fun addListener(listener: (State) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners -= listener
    }

    private fun setState(s: State) {
        state = s
        ThreadPool.onUi { listeners.toList().forEach { it(s) } }
    }

    /**
     * Conecta a um Roku: sobe o servidor DLNA com a biblioteca inteira e lança o
     * Roku Media Player. Grava o aparelho no [Settings] para o próximo toque ser direto.
     *
     * A árvore é construída **agora**, na thread de rede: MediaStore em 3 mil faixas leva
     * centenas de milissegundos, e congelar isso na UI transformaria o toque no botão em ANR.
     */
    fun connect(context: Context, device: RokuEcp.Device) {
        if (state is State.Connected || state is State.Busy) return
        val app = context.applicationContext
        setState(State.Busy(device.friendlyName))
        ThreadPool.postNetwork {
            val ok = runCatching {
                val (entries, pathById) = buildEntries(app)
                val nodes = ContentTree.build(entries)
                val uuid = serverUuid ?: Settings.deviceId(app).also { serverUuid = it }
                val srv = DlnaServer(app, uuid, nodes, { id -> pathById[id] })
                if (!srv.start()) return@runCatching false
                server = srv
                // Acha o ID real do Roku Media Player (muda entre firmware) e lança.
                val appId = RokuEcp.rmpAppId(device.host) ?: "12"
                RokuEcp.launch(device.host, appId)
                Settings.setTvDevice(app, device.host, device.friendlyName)
                true
            }.getOrDefault(false)
            if (ok) {
                setState(State.Connected(device))
            } else {
                server?.stop()
                server = null
                setState(State.Idle)
            }
        }
    }

    /** Reconecta à última TV pareada (o caminho do "tocar na TV" de um toque só). */
    fun reconnect(context: Context): Boolean {
        val (host, name) = Settings.tvDevice(context) ?: return false
        connect(context, RokuEcp.Device(host, name, "Roku"))
        return true
    }

    fun disconnect() {
        server?.stop()
        server = null
        setState(State.Idle)
    }

    /**
     * Tecla ECP para a TV conectada (Play/Pause/Forward/Rewind/Volume…). Fire-and-forget:
     * o ECP não devolve resultado que valha bloquear a UI, e o estado real da TV se
     * reflete no próprio Roku Media Player.
     */
    fun keypress(key: String) {
        val device = (state as? State.Connected)?.device ?: return
        ThreadPool.postNetwork {
            runCatching { RokuEcp.keypress(device.host, key) }
        }
    }

    /** Estado do player da TV (para o painel mostrar tocou/pausou), lido sob demanda. */
    fun mediaState(onResult: (RokuEcp.PlayerState?) -> Unit) {
        val device = (state as? State.Connected)?.device ?: return onResult(null)
        ThreadPool.postNetwork {
            val s = runCatching { RokuEcp.mediaPlayer(device.host) }.getOrNull()
            ThreadPool.onUi { onResult(s) }
        }
    }

    /** Abre de novo o Roku Media Player na TV (útil quando o usuário saiu para o menu). */
    fun relaunch() {
        val device = (state as? State.Connected)?.device ?: return
        ThreadPool.postNetwork {
            val appId = RokuEcp.rmpAppId(device.host) ?: "12"
            runCatching { RokuEcp.launch(device.host, appId) }
        }
    }

    /**
     * A biblioteca vira entradas DLNA, **e** o mapa fileId→origem do bytes (montado na
     * mesma ida ao MediaStore — buscar o path depois, faixa a faixa, seria uma consulta
     * por música para algo que a query já devolveu).
     *
     * Só mídia **local** com extensão que a TV toca ([ContentTree.mimeFor]); rádio/stream
     * (URL) fica de fora — o Roku toca http direto pelo próprio Media Player, e servir
     * fluxo de rede via DLNA seria o app no meio do caminho à toa.
     */
    private fun buildEntries(context: Context): Pair<List<ContentTree.Entry>, Map<String, String>> {
        val out = ArrayList<ContentTree.Entry>()
        val paths = HashMap<String, String>()
        runCatching {
            Library.allSongs(context).forEach { song ->
                if (song.isRadio || song.isStream || song.isVideo) return@forEach
                val name = File(song.path).name
                val mime = ContentTree.mimeFor(name) ?: return@forEach
                val size = runCatching { File(song.path).length() }.getOrDefault(0L)
                val fileId = "m${song.id}"
                paths[fileId] = song.path
                out += ContentTree.Entry(
                    fileId = fileId,
                    title = song.title.ifBlank { name },
                    artist = song.artist.takeIf { it.isNotBlank() && it != "<unknown>" },
                    album = song.album.takeIf { it.isNotBlank() && it != "<unknown>" },
                    mime = mime,
                    size = size,
                    durationMs = song.durationMs,
                    isVideo = false,
                )
            }
        }
        runCatching {
            VideoLibrary.all(context).forEach { video ->
                val name = File(video.path).name
                val mime = ContentTree.mimeFor(name) ?: return@forEach
                val fileId = "v${video.id}"
                // Vídeo: content:// do MediaStore (scoped storage nega o caminho solto;
                // o FileDescriptor continua legível e seekable — é o que o DlnaServer serve).
                paths[fileId] = VideoLibrary.contentUri(video.id).toString()
                out += ContentTree.Entry(
                    fileId = fileId,
                    title = video.title.ifBlank { name },
                    artist = null,
                    album = null,
                    mime = mime,
                    size = video.sizeBytes,
                    durationMs = video.durationMs,
                    isVideo = true,
                )
            }
        }
        return out to paths
    }

    /** UUID do servidor, para teste/debug. */
    internal fun uuidFor(context: Context): String =
        serverUuid ?: Settings.deviceId(context.applicationContext).also { serverUuid = it }

    /** Gera um UUID novo (só para inicialização limpa — o padrão vem do deviceId). */
    fun novoUuid(): String = UUID.randomUUID().toString()
}
