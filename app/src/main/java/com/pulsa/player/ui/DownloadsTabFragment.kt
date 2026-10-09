package com.pulsa.player.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity
import com.pulsa.player.media.DownloadService
import com.pulsa.player.media.DownloadStore
import com.pulsa.player.ui.adapter.DownloadsAdapter

/**
 * F2 — aba "Baixados": os vídeos PeerTube que estão no aparelho, e só eles.
 *
 * A tela é uma `RecyclerView` própria e não uma lista dentro do `VideosTabFragment`. A
 * busca mostra o que existe lá fora e some quando a rede cai; esta mostra o que existe
 * aqui e precisa abrir sem internet nenhuma. Juntar as duas na mesma lista fazia o
 * "Baixados" sumir junto com a busca no primeiro túnel — que é o teste que o usuário faz
 * sem pensar.
 *
 * Ela **observa** o [DownloadStore] em vez de recarregar no `onResume`. Recarregar no
 * `onResume` funciona enquanto a aba está aberta e erra justo no caso que importa:
 * baixar pede para sair da aba, e o estado bom do download está no serviço, não na tela.
 */
class DownloadsTabFragment : Fragment() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var emptyView: TextView
    private var observer: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
            setPadding(0, 4.dp, 0, 96.dp)
        }
        adapter = DownloadsAdapter(onClick = ::play, onMenu = ::menu)
        list.adapter = adapter
        root.addView(
            list,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0).apply { weight = 1f }
        )

        emptyView = TextView(ctx).apply {
            text = getString(R.string.downloads_empty)
            gravity = Gravity.CENTER
            setPadding(32.dp, 96.dp, 32.dp, 32.dp)
            setTextColor(resources.getColor(R.color.text_secondary, null))
            visibility = View.GONE
        }
        root.addView(
            emptyView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        DownloadStore.ensureLoaded(requireContext())
        val obs: () -> Unit = { render() }
        observer = obs
        DownloadStore.addObserver(obs)
        render()
    }

    override fun onDestroyView() {
        observer?.let { DownloadStore.removeObserver(it) }
        observer = null
        super.onDestroyView()
    }

    private fun render() {
        if (!isAdded) return
        val items = DownloadStore.list()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun play(item: DownloadStore.Item) {
        val ctx = context ?: return
        if (!item.isDone()) {
            // Tocar numa linha que ainda está baixando não pode abrir o player: o arquivo
            // não existe ainda. Um aviso e nada mais deixava a tela sem resposta, e o que o
            // usuário quer nesse momento é cancelar ou continuar — então abre o menu.
            menu(item)
            return
        }
        val file = DownloadStore.fileFor(ctx, item)
        if (!file.exists()) {
            // O registro sobrevive ao arquivo (limpeza do sistema, "limpar dados"): melhor
            // avisar do que abrir o player e ficar numa tela preta sem explicação.
            toast(R.string.downloads_failed)
            return
        }
        // `Uri.fromFile` e não a string crua: o `VideoPlayerActivity` entrega a URL para o
        // Media3, e um caminho sem esquema é tratado como texto, não como arquivo.
        //
        // O caminho vai no título de propósito, para a tela de vídeo mostrar de onde o
        // arquivo veio. O aviso de "Assistindo offline" que estava aqui mentia: ele
        // aparecia no instante em que o player era aberto, sem esperar a reprodução
        // começar, então confirmava uma coisa que ainda não tinha acontecido.
        VideoPlayerActivity.startStream(
            ctx, Uri.fromFile(file).toString(), item.title,
            uuid = item.id, pageUrl = item.pageUrl
        )
    }

    private fun menu(item: DownloadStore.Item) {
        val ctx = context ?: return
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (item.isDone()) {
            labels += ctx.getString(R.string.video_play)
            actions += { play(item) }
        }
        if (item.isActive()) {
            // Pausar não é o mesmo que cancelar: o segundo mata a tentativa (vira `CANCELLED`,
            // some da fila ativa), o primeiro congela o `.part` para seguir depois (vira `PAUSED`).
            labels += ctx.getString(R.string.downloads_pause)
            actions += {
                DownloadService.pause(ctx, item.id)
            }
            labels += ctx.getString(R.string.downloads_cancel)
            actions += {
                DownloadService.cancel(ctx, item.id)
                toast(R.string.downloads_cancelled)
            }
        }
        if (item.status == DownloadStore.Status.FAILED || item.status == DownloadStore.Status.CANCELLED ||
            item.status == DownloadStore.Status.PAUSED) {
            // "Continuar" e não "Tentar de novo": quando existe `.part`, o serviço retoma
            // pelo `Range` e o usuário não perde o que já baixou.
            labels += ctx.getString(R.string.downloads_continue)
            actions += {
                DownloadService.retry(ctx, item.id)
                toast(R.string.download_start_queued)
            }
        }

        labels += ctx.getString(R.string.downloads_delete)
        actions += {
            AlertDialog.Builder(ctx)
                .setTitle(R.string.downloads_delete)
                .setMessage(item.title)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.downloads_delete) { _, _ ->
                    DownloadService.delete(ctx, item.id)
                    toast(R.string.download_deleted)
                }
                .show()
        }

        AlertDialog.Builder(ctx)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
