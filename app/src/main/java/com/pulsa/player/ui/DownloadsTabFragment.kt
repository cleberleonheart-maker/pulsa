package com.pulsa.player.ui

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.VideoPlayerActivity

import com.pulsa.player.core.Helper
import com.pulsa.player.core.Settings


import com.pulsa.player.media.DownloadService
import com.pulsa.player.media.DownloadStore
import com.pulsa.player.media.OfficialMediaResolver
import com.pulsa.player.media.UrlDownloader
import com.pulsa.player.ui.adapter.DownloadsAdapter
import com.pulsa.player.work.PulsaWork

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


    // Barra de 512 MB: abaixo disto, o Android começa a chiar (limpeza de cache, apps que
    // se recusam a atualizar), então o aviso do app tem que acender antes, não junto.
    private val LOW_FREE_BYTES = 512L * 1024L * 1024L

    private lateinit var adapter: DownloadsAdapter
    private lateinit var emptyView: TextView
    private lateinit var banner: TextView

    private var observer: (() -> Unit)? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }


        // Fora da `RecyclerView`: o aviso de espaço tem que aparecer mesmo com a lista
        // vazia — o caso que importa é "vou baixar e não cabe", antes da primeira linha
        // existir.
        banner = TextView(ctx).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(resources.getColor(R.color.text_secondary, null))
            setBackgroundResource(R.drawable.bg_row_glass)
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        }
        root.addView(
            banner,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Baixar por URL de arquivo: a mesma entrada das Configurações, agora aqui na aba —
        // o ponto de quem só quer encher o cartão (música, palestra, episódio avulso) sem
        // passar pela busca do PeerTube.
        val addUrl = MaterialButton(ctx).apply {
            text = getString(R.string.downloads_add_url)
            setOnClickListener { promptDownloadUrl() }
        }
        root.addView(
            addUrl,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(16.dp, 4.dp, 16.dp, 4.dp)
            }
        )



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

        refreshBanner()
    }

    /**
     * Aviso de espaço no topo da aba.
     *
     * Duas coisas, e elas se complementam: o teto que o usuário escolheu (mostrado sempre
     * que existe, porque é o "quanto ainda dá pra baixar") e o próprio cartão, que avisa
     * antes do sistema avisar por ele — o espaço livre cair para poucos MB geralmente é
     * tarde demais para o usuário entender de onde veio a parada.
     *
     * Roda dentro do `render()` do observador: mudou o estado do download, muda o aviso.
     */
    private fun refreshBanner() {
        val ctx = context ?: return
        val messages = ArrayList<String>()

        val limitMb = Settings.downloadLimitMb(ctx)
        if (limitMb > 0) {
            val used = DownloadStore.usedBytes(ctx)
            val limit = limitMb * 1024L * 1024L
            messages += getString(R.string.downloads_used_cap, Helper.formatBytes(used), Helper.formatBytes(limit))
            if (used >= limit) {
                messages += getString(R.string.downloads_warning_limit)
            }
        }

        val free = runCatching { DownloadStore.dir(ctx).usableSpace }.getOrDefault(0L)
        if (free in 1 until LOW_FREE_BYTES) {
            messages += getString(R.string.downloads_warning_free, Helper.formatBytes(free))
        }

        if (messages.isEmpty()) {
            banner.visibility = View.GONE
        } else {
            banner.text = messages.joinToString("\n")
            banner.visibility = View.VISIBLE
        }


    }

    private fun promptDownloadUrl() {
        val ctx = requireContext()
        val urlInput = EditText(ctx).apply {
            hint = getString(R.string.download_url_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.download_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        body.addView(urlInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(
            nameInput,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12.dp
            }
        )
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.downloads_add_url)
            .setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.download) { _, _ ->
                startUrlDownload(urlInput.text.toString().trim(), nameInput.text.toString().trim())
            }
            .show()
    }

    /**
     * Mesmo caminho das Configurações: valida (http/https + não é página), joga no
     * WorkManager e avisa quando o arquivo cai no MediaStore.
     */
    private fun startUrlDownload(url: String, name: String) {
        val ctx = requireContext()
        // #277 – resolver na rede em background; no main thread a chamada HTTP falhava.
        OfficialMediaResolver.resolveAsync(url) { resolved ->
            val target = resolved?.directUrl ?: url
            if (resolved == null) {
                val errorRes = UrlDownloader.validate(url)
                if (errorRes != null) {
                    toast(errorRes)
                } else {
                    enqueueDownload(ctx, target, name)
                }
            } else {
                enqueueDownload(ctx, target, name.ifEmpty { resolved.name.orEmpty() })
            }
        }
    }

    private fun enqueueDownload(ctx: Context, url: String, name: String) {
        val finalName = name.ifEmpty { UrlDownloader.guessName(url) }
        val job = PulsaWork.download(ctx, url, finalName)
        if (job == null) {
            toast(R.string.download_failed, finalName)
            return
        }
        toast(R.string.download_started)
        val app = ctx.applicationContext
        PulsaWork.watchDownload(ctx, job) { ok, _ ->
            val message = if (ok) {
                app.getString(R.string.download_done, finalName)
            } else {
                app.getString(R.string.download_failed, finalName)
            }
            Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
        }
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

    private fun toast(res: Int, vararg args: Any) {
        Toast.makeText(requireContext(), getString(res, *args), Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
