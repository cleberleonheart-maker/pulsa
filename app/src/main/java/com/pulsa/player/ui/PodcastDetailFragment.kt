package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.playback.Playback
import com.pulsa.player.podcast.Episode
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastFiles
import com.pulsa.player.ui.adapter.PodcastEpisodeAdapter
import com.pulsa.player.work.PulsaWork

/**
 * F3 — os episódios de um podcast.
 *
 * É a tela que decide se a assinatura valeu a pena, então o episódio é **item de fila como os
 * outros**: tocar aqui monta a fila com os episódios a partir do escolhido, e não com o
 * episódio só. Um podcast ouvido em sequência é o caso comum, e a fila unificada (F2b) já sabe
 * item de podcast.
 *
 * "Marcar como ouvido" é a ação que só existe aqui, e é a que faz podcast não ser música: sem
 * ela a lista cresce para sempre e o usuário perde a noção do que já ouviu.
 */
class PodcastDetailFragment : Fragment() {

    private var header: View? = null
    private var headerArt: ImageView? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var adapter: PodcastEpisodeAdapter? = null
    private var loading = false
    private var episodes: List<Episode> = emptyList()

    private val podcastId: Long get() = arguments?.getLong(ARG_ID, 0L) ?: 0L
    private val podcastTitle: String get() = arguments?.getString(ARG_TITLE).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        header = view.findViewById(R.id.header_container)
        headerArt = view.findViewById(R.id.header_art)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)

        headerTitle?.text = podcastTitle
        headerArt?.setImageResource(R.drawable.ic_mic)
        view.findViewById<ImageView>(R.id.header_menu).visibility = View.GONE
        view.findViewById<View>(R.id.empty_action).visibility = View.GONE

        val a = PodcastEpisodeAdapter(
            fallbackArt = arguments?.getString(ARG_ART).orEmpty(),
            onClick = { ep -> playFrom(ep) },
            onMenu = { ep -> showEpisodeMenu(ep) }
        )
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (view == null || loading || podcastId <= 0L) return
        val app = context?.applicationContext ?: return
        loading = true
        ThreadPool.post {
            val list = runCatching { PodcastDb.get(app).episodes(podcastId) }
                .getOrDefault(emptyList())
            ThreadPool.onUi {
                loading = false
                if (isAdded) bind(list)
            }
        }
    }

    private fun bind(list: List<Episode>) {
        episodes = list
        adapter?.items = list
        header?.visibility = View.VISIBLE

        val unplayed = list.count { !it.played }
        // Autor e contagem juntos, com o autor de fora quando o feed não publica: a capa e o
        // título já estão no cabeçalho, o que falta é dizer o que está em cima e o que falta.
        val author = arguments?.getString(ARG_AUTHOR).orEmpty()
        val state = if (unplayed > 0) {
            if (unplayed == 1) {
                getString(R.string.podcast_one_unplayed)
            } else {
                getString(R.string.podcast_n_unplayed, unplayed)
            }
        } else {
            getString(R.string.podcast_all_played)
        }
        headerSubtitle?.text = if (author.isBlank()) state else "$author · $state"

        val empty = view?.findViewById<View>(R.id.empty_view)
        val emptyText = view?.findViewById<TextView>(R.id.empty_text)
        emptyText?.text = getString(R.string.podcast_no_episodes)
        empty?.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    /**
     * Toca do episódio escolhido em diante.
     *
     * A fila é montada com os **mesmos** itens da lista (mesma ordem, mesmos ids) — é o que faz
     * o próximo episódio vir no lugar do que o usuário esperaria, e o que mantém a identidade
     * `e:<id>` da fila alinhada com o que está na tela.
     */
    private fun playFrom(target: Episode) {
        val db = context?.applicationContext?.let { PodcastDb.get(it) } ?: return
        val index = episodes.indexOfFirst { it.id == target.id }
        if (index < 0) return
        val songs = episodes.map { db.toSong(podcastTitle, it) }
        adapter?.playingId = target.id
        Playback.start(songs, index)
    }

    private fun showEpisodeMenu(ep: Episode) {
        val ctx = context ?: return
        // O menu é montado com o estado **atual** do episódio: "Baixar" e "Apagar download"
        // nunca convivem, e um item que mente sobre o que faz é pior do que um item a menos.
        val downloaded = ep.filePath.isNotBlank()
        val labels = ArrayList<String>(4)
        labels += getString(R.string.podcast_play_now)
        labels += getString(R.string.add_to_queue)
        labels += getString(
            if (ep.played) R.string.podcast_mark_unplayed else R.string.podcast_mark_played
        )
        // Baixar só faz sentido com endereço de rede: um episódio em vídeo sem enclosure não
        // tem o que baixar, e oferecer o botão seria um convite a esperar por um erro.
        val canDownload = !downloaded && ep.audioUrl.startsWith("http")
        if (canDownload || downloaded) {
            labels += getString(
                if (downloaded) R.string.podcast_download_delete else R.string.podcast_download
            )
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle(ep.title.ifBlank { getString(R.string.podcast_episode) })
            .setItems(labels.toTypedArray()) { d, which ->
                when (which) {
                    0 -> playFrom(ep)
                    1 -> enqueue(ep)
                    2 -> togglePlayed(ep)
                    else -> if (downloaded) deleteDownload(ep) else download(ep)
                }
                d.dismiss()
            }
            .show()
    }

    /**
     * Baixa o episódio pelo mesmo caminho das Configurações (job com constraint de rede).
     *
     * O nome é derivado do **id do episódio**, não do título: título tem acento, barra e
     * tamanho variável, e o que precisa ser legível no MediaStore é o arquivo. O `Uri` que o
     * job devolve é o que o `PodcastDb.setFilePath` guarda, então o nome serve só de rótulo —
     * mas rótulo ilegível é o que aparece na galeria do sistema.
     */
    private fun download(ep: Episode) {
        val ctx = context ?: return
        val app = ctx.applicationContext
        val job = PulsaWork.download(app, ep.audioUrl, "podcast-ep${ep.id}")
        if (job == null) {
            Toast.makeText(ctx, failureMessage(ep), Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(ctx, R.string.download_started, Toast.LENGTH_SHORT).show()
        // O callback chega depois desta tela poder ter saído: o Toast usa o contexto de
        // aplicação e o `isAdded` decide se a lista pode ser recarregada.
        PulsaWork.watchDownload(app, job) { ok, path ->
            val message = when {
                !ok || path.isBlank() -> failureMessage(ep)
                else -> {
                    ThreadPool.post { PodcastDb.get(app).setFilePath(ep.id, path) }
                    ctx.getString(R.string.podcast_download_done)
                }
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
            if (ok && isAdded) load()
        }
    }

    /** Mesma frase de erro das Configurações, com o episódio em vez do nome do arquivo. */
    private fun failureMessage(ep: Episode): String =
        getString(R.string.download_failed, ep.title.ifBlank { getString(R.string.podcast_episode) })

    /**
     * Apaga o arquivo e limpa o `file_path`, para o episódio voltar a abrir pela URL.
     *
     * A ordem importa: apagar o arquivo **antes** de limpar o banco é o que evita o inverso
     * perigoso — banco limpo e arquivo vivo, que ninguém mais sabe onde está.
     */
    private fun deleteDownload(ep: Episode) {
        val app = context?.applicationContext ?: return
        ThreadPool.post {
            PodcastFiles.delete(app, ep.filePath)
            PodcastDb.get(app).setFilePath(ep.id, "")
            ThreadPool.onUi {
                if (isAdded) load()
                Toast.makeText(app, R.string.download_deleted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * "Adicionar à fila" de um episódio: um item só, e o aviso é o do número que o motor aceitou.
     *
     * O `QueueKey` recusa o que já está na fila (ver `filterNew`), e o retorno `0` é o mesmo
     * sinal de "não entrou" — por isso a mensagem diz "já está na fila" em vez de "não deu".
     */
    private fun enqueue(ep: Episode) {
        val ctx = context ?: return
        val db = PodcastDb.get(ctx)
        val added = Playback.enqueue(listOf(db.toSong(podcastTitle, ep)))
        Toast.makeText(
            ctx,
            if (added > 0) getString(R.string.podcast_added_to_queue)
            else getString(R.string.podcast_already_in_queue),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun togglePlayed(ep: Episode) {
        val app = context?.applicationContext ?: return
        ThreadPool.post {
            PodcastDb.get(app).setPlayed(ep.id, !ep.played)
            ThreadPool.onUi { if (isAdded) load() }
        }
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_TITLE = "title"
        private const val ARG_AUTHOR = "author"
        private const val ARG_ART = "art"

        /**
         * O podcast vai inteiro por argumento, e não só o id.
         *
         * A lista de assinaturas já tem título, autor e capa na mão: passá-los evita a tela
         * ficar com o cabeçalho vazio enquanto o banco é consultado, e o detalhe volta a
         * carregar tudo de novo quando o usuário reabre pela mesma assinatura.
         */
        fun forPodcast(id: Long, title: String, author: String, artwork: String) =
            PodcastDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, id)
                    putString(ARG_TITLE, title)
                    putString(ARG_AUTHOR, author)
                    putString(ARG_ART, artwork)
                }
            }
    }
}