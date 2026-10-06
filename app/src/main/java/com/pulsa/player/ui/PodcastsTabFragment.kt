package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.db.FeedRef
import com.pulsa.player.data.db.PodcastFeedRow
import com.pulsa.player.podcast.PodcastDb
import com.pulsa.player.podcast.PodcastFiles
import com.pulsa.player.podcast.PodcastNet
import com.pulsa.player.podcast.PodcastSync
import com.pulsa.player.ui.adapter.PodcastFeedAdapter
import com.pulsa.player.ui.adapter.PodcastFoundAdapter

/**
 * F3 — as assinaturas de podcast.
 *
 * A tela é curta de propósito: o que o podcast tem de difícil não é a lista, é **assinar**.
 * Assinar por endereço colado e por busca são os dois caminhos, e ambos terminam no mesmo
 * lugar — [PodcastSync.subscribeByUrl], que busca o feed e grava os episódios. A alternativa
 * (gravar só os metadados que a busca devolveu) deixaria o podcast sem episódio nenhum até o
 * próximo refresh, e a lista pareceria quebrada.
 *
 * O erro do feed fica **na linha** ([com.pulsa.player.ui.adapter.PodcastFeedAdapter]) e não num
 * aviso que passa: feed que dá 404 continua assinado, e o usuário precisa saber por que a lista
 * não cresce.
 */
class PodcastsTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var emptyAction: MaterialButton? = null
    private var adapter: PodcastFeedAdapter? = null
    private var loading = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_list, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        headerContainer = view.findViewById(R.id.header_container)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        emptyView = view.findViewById(R.id.empty_view)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        emptyAction?.apply {
            setText(R.string.podcast_subscribe)
            setOnClickListener { showSubscribeDialog() }
        }

        // A arte do cabeçalho sumiria: aqui ele é contador ("4 podcasts"), não capa. O `+` é a
        // única ação que importa nesta tela.
        view.findViewById<ImageView>(R.id.header_art).visibility = View.GONE
        view.findViewById<ImageView>(R.id.header_menu).apply {
            visibility = View.VISIBLE
            contentDescription = getString(R.string.podcast_subscribe)
            setOnClickListener { showSubscribeDialog() }
        }

        val a = PodcastFeedAdapter(
            onClick = { feed -> (activity as? MainActivity)?.openPodcast(feed) },
            onMenu = { feed -> showFeedMenu(feed) }
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
        if (view == null || loading) return
        val app = context?.applicationContext ?: return
        loading = true
        ThreadPool.post {
            val feeds = runCatching { PodcastDb.get(app).feeds() }.getOrDefault(emptyList())
            ThreadPool.onUi {
                loading = false
                if (isAdded) bind(feeds)
            }
        }
    }

    private fun bind(feeds: List<PodcastFeedRow>) {
        adapter?.items = feeds
        if (feeds.isEmpty()) {
            headerContainer?.visibility = View.GONE
            showEmpty(getString(R.string.podcast_empty), showAction = true)
        } else {
            headerContainer?.visibility = View.VISIBLE
            headerTitle?.text = getString(R.string.podcasts)
            headerSubtitle?.text = if (feeds.size == 1) {
                getString(R.string.one_podcast)
            } else {
                getString(R.string.n_podcasts, feeds.size)
            }
            showEmpty(null, showAction = false)
        }
    }

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
        emptyAction?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun reload() {
        loading = false
        load()
    }

    // ---- menu da assinatura ---------------------------------------------------------------

    private fun showFeedMenu(feed: PodcastFeedRow) {
        val ctx = context ?: return
        val title = feed.title?.takeIf { it.isNotBlank() } ?: getString(R.string.podcast)
        val labels = arrayOf(
            getString(R.string.podcast_refresh),
            getString(R.string.podcast_unsubscribe)
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(title)
            .setItems(labels) { d, which ->
                when (which) {
                    0 -> refreshOne(ctx, feed)
                    1 -> confirmUnsubscribe(ctx, feed)
                }
                d.dismiss()
            }
            .show()
    }

    private fun refreshOne(ctx: android.content.Context, feed: PodcastFeedRow) {
        Toast.makeText(ctx, R.string.podcast_refreshing, Toast.LENGTH_SHORT).show()
        ThreadPool.postNetwork {
            val ref = FeedRef(feed.id, feed.feedUrl)
            val result = PodcastSync.refreshFeed(PodcastDb.get(ctx), ref)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                if (result.ok) {
                    Toast.makeText(
                        ctx,
                        if (result.added > 0) {
                            getString(R.string.podcast_new_episodes, result.added)
                        } else {
                            getString(R.string.podcast_up_to_date)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // A assinatura continua na lista: quem dá 404 pode voltar, e apagar em
                    // silêncio seria o jeito rápido de perder meses de histórico.
                    Toast.makeText(ctx, result.error, Toast.LENGTH_LONG).show()
                }
                reload()
            }
        }
    }

    private fun confirmUnsubscribe(ctx: android.content.Context, feed: PodcastFeedRow) {
        val title = feed.title?.takeIf { it.isNotBlank() } ?: getString(R.string.podcast)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(getString(R.string.podcast_unsubscribe))
            .setMessage(getString(R.string.podcast_unsubscribe_message, title))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.podcast_unsubscribe) { _, _ ->
                unsubscribe(ctx, feed)
            }
            .show()
    }

    private fun unsubscribe(ctx: android.content.Context, feed: PodcastFeedRow) {
        ThreadPool.post {
            // Os arquivos baixados são apagados junto: devolver o caminho dos só deletados
            // deixaria o arquivo órfão ocupando espaço, sem nenhuma linha no banco que
            // permitisse limpá-lo depois.
            val files = runCatching { PodcastDb.get(ctx).unsubscribe(feed.id) }
                .getOrDefault(emptyList())
            // `content://` do MediaStore entra por [PodcastFiles.delete]: `File(path)` não
            // apagaria nada e o arquivo ficaria órfão, sem linha que o localizasse depois.
            for (path in files) PodcastFiles.delete(ctx, path)
            ThreadPool.onUi { if (isAdded) reload() }
        }
    }

    // ---- assinar ---------------------------------------------------------------------------

    /**
     * O diálogo serve para os dois caminhos, e o que decide é [PodcastNet.looksLikeFeedUrl]:
     * endereço colado assina direto, texto livre vai para a busca.
     *
     * Buscar e assinar não é a mesma coisa: o `feedUrl` do resultado da iTunes é o que se
     * assina, mas os episódios só existem depois de buscar o feed — por isso o clique refaz a
     * busca do feed em vez de gravar o que a busca devolveu.
     */
    private fun showSubscribeDialog() {
        val ctx = context ?: return
        val view = layoutInflater.inflate(R.layout.dialog_podcast_add, null)
        val query = view.findViewById<EditText>(R.id.pod_query)
        val status = view.findViewById<TextView>(R.id.pod_status)
        val loadingView = view.findViewById<ProgressBar>(R.id.pod_loading)
        val results = view.findViewById<RecyclerView>(R.id.pod_results)

        var dialog: AlertDialog? = null
        val resultAdapter = PodcastFoundAdapter { found ->
            subscribe(ctx, found.feedUrl, dialog, loadingView, status)
        }
        results.layoutManager = LinearLayoutManager(ctx)
        results.adapter = resultAdapter

        dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.podcast_subscribe)
            .setView(view)
            .setNegativeButton(R.string.close, null)
            .create()

        fun submit(text: String) {
            val raw = text.trim()
            if (raw.isEmpty()) return
            if (PodcastNet.looksLikeFeedUrl(raw)) {
                subscribe(ctx, raw, dialog, loadingView, status)
                return
            }
            loadingView.visibility = View.VISIBLE
            status.visibility = View.GONE
            results.visibility = View.GONE
            resultAdapter.items = emptyList()
            PodcastNet.search(raw) { result ->
                loadingView.visibility = View.GONE
                val found = result.getOrNull().orEmpty()
                resultAdapter.items = found
                results.visibility = if (found.isEmpty()) View.GONE else View.VISIBLE
                if (found.isEmpty()) {
                    status.visibility = View.VISIBLE
                    // Rede caída e busca que não casou são coisas diferentes, com conselhos
                    // diferentes: uma se resolve trocando de rede, a outra trocando as palavras.
                    status.setText(
                        if (result.isFailure) R.string.podcast_search_error else R.string.podcast_search_empty
                    )
                }
            }
        }

        view.findViewById<View>(R.id.pod_search_btn).setOnClickListener {
            submit(query.text?.toString().orEmpty())
        }
        query.setOnEditorActionListener { _, _, _ ->
            submit(query.text?.toString().orEmpty())
            true
        }

        dialog.show()
    }

    /**
     * Assina um endereço e recarrega a lista.
     *
     * O diálogo fecha **depois** do resultado: fechar antes deixaria a tela vazia sem retorno
     * nenhum se o feed falhasse, e o usuário ficaria sem saber se assinou ou não.
     */
    private fun subscribe(
        ctx: android.content.Context,
        url: String,
        dialog: AlertDialog?,
        loadingView: View,
        status: TextView
    ) {
        loadingView.visibility = View.VISIBLE
        status.visibility = View.VISIBLE
        status.setText(R.string.podcast_loading)
        ThreadPool.postNetwork {
            val outcome = PodcastSync.subscribeByUrl(PodcastDb.get(ctx), url)
            ThreadPool.onUi {
                loadingView.visibility = View.GONE
                if (outcome.ok) {
                    Toast.makeText(ctx, R.string.podcast_subscribed, Toast.LENGTH_SHORT).show()
                    dialog?.dismiss()
                    reload()
                } else {
                    status.visibility = View.VISIBLE
                    status.text = outcome.error
                }
            }
        }
    }

    fun title(): String = requireContext().getString(R.string.podcasts)
}