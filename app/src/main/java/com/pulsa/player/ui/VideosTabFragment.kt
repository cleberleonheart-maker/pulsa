package com.pulsa.player.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.media3.common.MimeTypes
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.core.Settings
import com.pulsa.player.data.PeerTube
import com.pulsa.player.data.VideoLibrary
import com.pulsa.player.media.DownloadService
import com.pulsa.player.media.DownloadStore
import com.pulsa.player.media.StreamKind
import com.pulsa.player.playback.Playback
import com.pulsa.player.model.Video
import com.pulsa.player.model.toSong
import com.pulsa.player.ui.adapter.PeerTubeAdapter
import com.pulsa.player.ui.adapter.VideoListAdapter
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.ThreadPool

class VideosTabFragment : Fragment() {

    private var list: RecyclerView? = null
    private var empty: View? = null
    private var emptyText: TextView? = null
    private var permissionBtn: View? = null
    private var headerSubtitle: TextView? = null
    private var adapter: VideoListAdapter? = null
    private var selectionBar: SelectionBar? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_videos, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.videos_list)
        empty = view.findViewById(R.id.videos_empty)
        emptyText = view.findViewById(R.id.videos_empty_text)
        permissionBtn = view.findViewById(R.id.videos_permission_btn)
        headerSubtitle = view.findViewById(R.id.videos_header_subtitle)
        val a = VideoListAdapter(
            onClick = { video, pos -> openPlayer(video, pos) },
            onMenu = { video, _ -> showMenu(video) }
        )
        adapter = a
        selectionBar = SelectionWiring.setUpSelection(this, view, a, { load() }) { videos ->
            addToQueue(videos)
        }
        list?.apply {
            layoutManager = LinearLayoutManager(this@VideosTabFragment.context)
            adapter = a
        }
        permissionBtn?.setOnClickListener { Permissions.requestVideo(requireActivity()) }
        view.findViewById<View>(R.id.videos_stream_btn)?.setOnClickListener { showStreamDialog() }
    }

    /**
     * F2: abre uma URL de stream (HLS/DASH/progressivo) direto no player.
     *
     * O botão fica no cabeçalho e não no menu do vídeo porque stream não vem da biblioteca:
     * serve justamente quando não há nenhum vídeo no aparelho, e o estado vazio da aba é
     * onde mais faz sentido aparecer isso.
     */
    private fun showStreamDialog() {
        val ctx = context ?: return
        val view = layoutInflater.inflate(R.layout.dialog_stream_url, null)
        val urlInput = view.findViewById<EditText>(R.id.stream_url)
        val titleInput = view.findViewById<EditText>(R.id.stream_title)
        val detected = view.findViewById<TextView>(R.id.stream_detected)

        // Fala o formato detectado enquanto o usuário digita: é o feedback que evita a falha
        // silenciosa do Media3 (URL sem extensão virando "vídeo comum" e morrendo no load).
        fun refreshDetected() {
            val mime = StreamKind.mimeTypeOf(urlInput.text?.toString().orEmpty())
            detected.visibility = View.VISIBLE
            detected.text = when (mime) {
                MimeTypes.APPLICATION_M3U8 -> getString(R.string.video_stream_detected, getString(R.string.video_stream_detected_hls))
                MimeTypes.APPLICATION_MPD -> getString(R.string.video_stream_detected, getString(R.string.video_stream_detected_dash))
                else -> {
                    if (urlInput.text?.isBlank() == true) {
                        detected.visibility = View.GONE
                        return
                    }
                    getString(R.string.video_stream_detected, getString(R.string.video_stream_detected_progressive))
                }
            }
        }
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = refreshDetected()
        })

        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.video_stream_title)
            .setView(view)
            .setPositiveButton(R.string.video_stream_play, null)
            .setNegativeButton(R.string.close, null)
            .create()

        view.findViewById<View>(R.id.stream_peertube_btn)?.setOnClickListener {
            // Fecha este diálogo e abre a busca: são dois diálogos independentes, e manter os
            // dois abertos ia dar duas camadas de modal sobre a tela de vídeo.
            dialog.dismiss()
            showPeerTubeDialog()
        }

        // O botão do "Tocar" é resolvido aqui (e não no setPositiveButton) porque a validação
        // precisa trocar o texto do botão para "erro" e manter o diálogo aberto quando o link
        // não serve — fechar e cuspir um Toast faz o usuário colar o link de novo.
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = urlInput.text?.toString().orEmpty()
                if (StreamKind.playableUrl(raw) == null) {
                    val blocked = raw.isNotBlank() && StreamKind.urlLooksLikePage(raw)
                    urlInput.error = getString(
                        if (blocked) R.string.video_stream_url_blocked else R.string.video_stream_url_invalid
                    )
                    return@setOnClickListener
                }
                VideoPlayerActivity.startStream(
                    ctx,
                    raw.trim(),
                    titleInput.text?.toString().orEmpty().trim()
                )
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * F2: busca no PeerTube e toque direto do resultado.
     *
     * Entra pelo mesmo caminho do link colado à mão — o resultado vira `stream:` com o HLS
     * (ou o MP4, se a instância não gerar HLS) e vai para a mesma tela de vídeo. Não há
     * caminho especial no player, que é o ponto: o PeerTube é só uma fonte que entrega
     * URL, a mesma que o CDN do seu servidor entregaria.
     */
    private fun showPeerTubeDialog() {
        val ctx = context ?: return
        val view = layoutInflater.inflate(R.layout.dialog_peertube_search, null)
        val query = view.findViewById<EditText>(R.id.pt_query)
        val instanceLabel = view.findViewById<TextView>(R.id.pt_instance)
        val status = view.findViewById<TextView>(R.id.pt_status)
        val loading = view.findViewById<ProgressBar>(R.id.pt_loading)
        val results = view.findViewById<RecyclerView>(R.id.pt_results)

        // Declarado antes do adapter porque os callbacks tocam o diálogo — e um `val` local
        // declarado depois da lambda que o captura não resolve no Kotlin.
        var dialog: AlertDialog? = null

        val adapter = PeerTubeAdapter(
            onClick = { item -> playPeerTube(ctx, item, dialog) },
            onMenu = { item -> showPeerTubeMenu(ctx, item, dialog) }
        )
        results.layoutManager = LinearLayoutManager(ctx)
        results.adapter = adapter

        instanceLabel.text = getString(R.string.peertube_instance, Settings.peerTubeInstance(ctx))
        instanceLabel.setOnClickListener { showInstanceDialog(ctx) }

        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.peertube_title)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()

        fun runSearch(text: String) {
            loading.visibility = View.VISIBLE
            status.visibility = View.GONE
            results.visibility = View.GONE
            adapter.items = emptyList()
            PeerTube.search(Settings.peerTubeInstance(ctx), text) { result ->
                if (!isAdded) return@search
                loading.visibility = View.GONE
                // Erro e "nada encontrado" são coisas diferentes e precisam de mensagens
                // diferentes: erro é instância caída/bloqueada, e a remedy é trocar; resultado
                // vazio é a busca que não casou, e a remedy é trocar as palavras. Dizer "nada
                // encontrado" para uma rede caída manda o usuário caçar palavra que existe.
                val items = result.getOrNull().orEmpty()
                adapter.items = items
                results.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
                if (items.isEmpty()) {
                    status.visibility = View.VISIBLE
                    status.text = getString(
                        if (result.isFailure) R.string.peertube_error else R.string.peertube_empty
                    )
                }
            }
        }

        val searchBtn = view.findViewById<View>(R.id.pt_search_btn)
        searchBtn.setOnClickListener { runSearch(query.text?.toString().orEmpty()) }
        query.setOnEditorActionListener { _, _, _ ->
            runSearch(query.text?.toString().orEmpty())
            true
        }

        dialog?.show()
    }

    /**
     * Resolve o stream e abre o player.
     *
     * A busca devolve só metadados (ver [PeerTube]), então o clique precisa de um pedido ao
     * endpoint do vídeo para descobrir o HLS/MP4. O diálogo só fecha **depois** disso: fechar
     * antes deixaria o usuário em tela vazia se a instância falhasse, sem retorno do que
     * aconteceu. O item fica marcado como "tocar" enquanto espera.
     */
    private fun playPeerTube(ctx: Context, item: PeerTube.Item, dialog: AlertDialog?) {
        val ready = item.streamUrl
        if (ready != null) {
            dialog?.dismiss()
            VideoPlayerActivity.startStream(ctx, ready, item.title, item.captions)
            return
        }
        Toast.makeText(ctx, R.string.peertube_resolving, Toast.LENGTH_SHORT).show()
        PeerTube.resolve(item) { resolved ->
            if (!isAdded) return@resolve
            val url = resolved?.streamUrl
            if (url == null) {
                Toast.makeText(ctx, R.string.peertube_no_stream, Toast.LENGTH_LONG).show()
                return@resolve
            }
            dialog?.dismiss()
            VideoPlayerActivity.startStream(ctx, url, resolved.title, resolved.captions)
        }
    }

    /**
     * Coloca o vídeo na fila de download.
     *
     * Igual ao [playPeerTube], o item da busca não tem stream: precisa do endpoint do vídeo
     * para descobrir o MP4. Mas aqui o destino não é o [streamUrl] e sim o
     * `downloadUrl`, porque baixar o HLS gravaria só o manifesto (ver [PeerTube.Item.downloadUrl]).
     */
    private fun downloadPeerTube(ctx: Context, item: PeerTube.Item, dialog: AlertDialog?) {
        if (DownloadStore.byId(item.uuid)?.isDone() == true) {
            Toast.makeText(ctx, R.string.download_already, Toast.LENGTH_SHORT).show()
            dialog?.dismiss()
            return
        }
        val ready = item.downloadUrl
        if (ready != null) {
            dialog?.dismiss()
            startDownload(ctx, item, ready)
            return
        }
        Toast.makeText(ctx, R.string.peertube_resolving, Toast.LENGTH_SHORT).show()
        PeerTube.resolve(item) { resolved ->
            if (!isAdded) return@resolve
            val url = resolved?.downloadUrl
            if (url == null) {
                Toast.makeText(ctx, R.string.download_not_available, Toast.LENGTH_LONG).show()
                return@resolve
            }
            dialog?.dismiss()
            startDownload(ctx, resolved, url)
        }
    }

    private fun startDownload(ctx: Context, item: PeerTube.Item, url: String) {
        DownloadStore.ensureLoaded(ctx)
        val ext = runCatching {
            val path = Uri.parse(url).path.orEmpty()
            val e = path.substringAfterLast('.', "").lowercase()
            if (e.length in 2..4 && e.all { it.isLetterOrDigit() }) e else "mp4"
        }.getOrDefault("mp4")
        // O nome do arquivo é o UUID e não o título: título tem `/`, acentos e emoji, e
        // vira `%20`/`..` no caminho. O título continua salvo no JSON para a lista.
        val fileName = "${item.uuid}.$ext"
        DownloadService.enqueue(
            context = ctx,
            id = item.uuid,
            title = item.title,
            url = url,
            pageUrl = item.pageUrl,
            fileName = fileName
        )
        val jaNaFila = DownloadStore.byId(item.uuid)?.isActive() == true
        Toast.makeText(
            ctx,
            if (jaNaFila) R.string.download_start_queued else R.string.download_start_running,
            Toast.LENGTH_SHORT
        ).show()
        BibliotecaFragment.pending = BibliotecaFragment.SECTION_DOWNLOADS
    }

    private fun showPeerTubeMenu(ctx: Context, item: PeerTube.Item, parent: AlertDialog?) {
        // "Baixar" em segundo: tocar é o que se faz 9 de 10 vezes com um resultado, e
        // apagar é a ação que o usuário procura quando quer o vídeo no aparelho.
        val labels = arrayOf(
            getString(R.string.video_play),
            getString(R.string.peertube_download),
            getString(R.string.peertube_open_browser),
            getString(R.string.peertube_copy_link)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(item.title)
            .setItems(labels) { d, which ->
                when (which) {
                    0 -> playPeerTube(ctx, item, parent)
                    1 -> downloadPeerTube(ctx, item, parent)
                    2 -> openExternal(ctx, item.pageUrl, "text/html")
                    3 -> copyToClipboard(ctx, item.pageUrl)
                }
                d.dismiss()
            }
            .show()
    }

    private fun openExternal(ctx: Context, url: String, mime: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { type = mime }
        runCatching { ctx.startActivity(intent) }.onFailure {
            Toast.makeText(ctx, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(ctx: Context, text: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clip.setPrimaryClip(ClipData.newPlainText("link", text))
    }

    /** Troca a instância do PeerTube — instância pública cai, e o padrão não pode ser fixo. */
    private fun showInstanceDialog(ctx: Context) {
        val input = EditText(ctx).apply {
            setText(Settings.peerTubeInstance(ctx))
            setHint(R.string.peertube_instance_hint)
            setSingleLine()
            val pad = (20 * ctx.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.peertube_change_instance)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Settings.setPeerTubeInstance(ctx, input.text?.toString().orEmpty())
                Toast.makeText(
                    ctx,
                    getString(R.string.peertube_instance_saved, Settings.peerTubeInstance(ctx)),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    fun load() {
        if (view == null) return
        if (loading) return
        val ctx = requireContext()
        if (!Permissions.hasVideo(ctx)) {
            permissionBtn?.visibility = View.VISIBLE
            emptyText?.text = getString(R.string.empty_no_permission)
            showEmpty(true)
            loading = false
            return
        }
        loading = true
        ThreadPool.post {
            val videos = try {
                VideoLibrary.all(ctx).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    adapter?.videos = videos
                    selectionBar?.setAvailable(videos.map { it.id })
                    headerSubtitle?.text = if (videos.isEmpty()) "" else getString(R.string.videos_count, videos.size)
                    if (videos.isEmpty()) {
                        permissionBtn?.visibility = View.GONE
                        emptyText?.text = getString(R.string.empty_videos)
                        showEmpty(true)
                    } else {
                        showEmpty(false)
                    }
                }
            }
        }
    }

    private fun showEmpty(on: Boolean) {
        empty?.visibility = if (on) View.VISIBLE else View.GONE
        list?.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun openPlayer(video: Video, pos: Int) {
        val ctx = context ?: return
        adapter?.highlightId = video.id
        val videos = adapter?.videos.orEmpty()
        if (videos.isEmpty()) {
            Toast.makeText(ctx, R.string.play_video_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val idx = videos.indexOfFirst { it.id == video.id }.let { if (it < 0) pos else it }
        VideoPlayerActivity.start(ctx, videos, idx)
    }

    private fun showMenu(video: Video) {
        val items = arrayOf(
            getString(R.string.video_play),
            getString(R.string.add_to_queue),
            getString(R.string.share_music)
        )
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(video.title)
            .setItems(items) { dialog, which ->
                when (which) {
                    0 -> openPlayer(video, -1)
                    1 -> addToQueue(listOf(video))
                    2 -> share(video)
                }
                dialog.dismiss()
            }
            .show()
    }

    /**
     * F2b — junta vídeo(s) ao fim da fila, sem trocar o que está tocando.
     *
     * "Adicionar à fila" é o caminho que **não** passa pela tela de vídeo: aquele continua
     * substituindo a fila da música e depois devolvendo-a na saída, porque precisa da tela
     * cheia para tocar. Aqui o vídeo entra na fila única e a música segue tocando — é o que
     * faz a fila ser de fato misturada.
     *
     * O aviso é o do número que o motor aceitou, não o do que foi pedido: pedir de novo um
     * vídeo que já está na fila devolve zero e [Playback.enqueue] não duplica. Confundir os
     * dois fazia o app dizer "1 vídeo na fila" sem ter feito nada.
     */
    private fun addToQueue(videos: List<Video>) {
        val ctx = context ?: return
        if (videos.isEmpty()) return
        val songs = videos.map { it.toSong(getString(R.string.tab_videos)) }
        val added = Playback.enqueue(songs)
        val msg = if (added > 0) {
            getString(R.string.video_added_to_queue, added)
        } else {
            getString(R.string.video_already_in_queue)
        }
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private fun share(video: Video) {
        val ctx = requireContext()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, VideoLibrary.contentUri(video.id))
            putExtra(Intent.EXTRA_TEXT, video.title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_music)))
        }.onFailure {
            android.widget.Toast.makeText(ctx, R.string.share_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun title(): String = requireContext().getString(R.string.tab_videos)
}
