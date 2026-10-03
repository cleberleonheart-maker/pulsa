package com.pulsa.player.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.media.DownloadStore
import java.net.URL
import java.util.LinkedHashMap

/**
 * F2 — a lista de downloads.
 *
 * Não pede o byte a byte a cada linha: o [DownloadStore] empurra o item inteiro, e o
 * adapter só redesenha o que mudou de verdade. Uma fila de dez vídeos em andamento atualiza
 * as dez linhas a cada 400 ms, e o RecyclerView refazendo todas as views a cada passo dava
 * um piscar visível no meio da lista.
 */
class DownloadsAdapter(
    private val onClick: (DownloadStore.Item) -> Unit,
    private val onMenu: (DownloadStore.Item) -> Unit
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    private var items: List<DownloadStore.Item> = emptyList()

    /** Thumbnails por UUID, com teto: a miniatura do PeerTube não vale ser carregada de novo. */
    private val thumbs = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }
    private val loading = HashSet<String>()

    fun submit(newItems: List<DownloadStore.Item>) {
        val progressoMudou = newItems.size == items.size &&
            newItems.indices.any { newItems[it].bytes != items[it].bytes }
        if (progressoMudou) {
            // Só os bytes andaram: atualizar a linha inteira faria a lista tremer e
            // perder o foco do toque. O `notifyItemRangeChanged` deixa o RecyclerView
            // reaproveitar os holders e sem animação.
            items = newItems
            notifyItemRangeChanged(0, newItems.size, PAYLOAD_PROGRESS)
            return
        }
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgress(holder, items[position])
            // O clique é religado aqui também, e não só o texto. Capturar o `item` no
            // `bind` foi o que produziu "a linha diz Baixado e o toque diz Baixando":
            // ao terminar, o `bytes` também mudava, então esta via rápida era usada, o
            // texto passava para "Baixado" e o clique continuava segurando a cópia antiga,
            // com status RUNNING — e o player recusava abrir o arquivo que já estava lá.
            hookClicks(holder)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    /** Item **atual** da posição, ou `null` se a lista mexeu entre o desenho e o toque. */
    fun itemAt(position: Int): DownloadStore.Item? = items.getOrNull(position)

    private fun hookClicks(holder: VH) {
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            itemAt(pos)?.let(onClick)
        }
        holder.menu.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            itemAt(pos)?.let(onMenu)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.boundId = item.id
        holder.title.text = item.title
        bindProgress(holder, item)

        holder.thumb.setImageResource(R.drawable.ic_download)
        holder.thumb.alpha = 0.55f
        thumbs[item.id]?.let {
            holder.thumb.setImageBitmap(it)
            holder.thumb.alpha = 1f
        } ?: loadThumb(holder, item)

        // Só um arquivo pronto toca. Tentar abrir uma fila ou um falhado daria tela preta.
        holder.play.visibility = if (item.isDone()) View.VISIBLE else View.INVISIBLE
        hookClicks(holder)
        holder.itemView.alpha = if (item.isDone()) 1f else 0.85f
    }

    private fun bindProgress(holder: VH, item: DownloadStore.Item) {
        val ctx = holder.itemView.context
        val status = when (item.status) {
            DownloadStore.Status.QUEUED -> ctx.getString(R.string.downloads_queued)
            DownloadStore.Status.RUNNING -> ctx.getString(R.string.downloads_running)
            DownloadStore.Status.DONE -> ctx.getString(R.string.downloads_done)
            DownloadStore.Status.FAILED -> item.error ?: ctx.getString(R.string.downloads_failed)
            DownloadStore.Status.CANCELLED -> ctx.getString(R.string.downloads_cancelled)
        }
        holder.meta.text = if (item.isActive()) {
            val size = if (item.total > 0L) {
                "${Helper.formatBytes(item.bytes)} / ${Helper.formatBytes(item.total)}"
            } else {
                Helper.formatBytes(item.bytes)
            }
            "$status · $size"
        } else {
            val size = Helper.formatBytes(item.bytes)
            "$status · $size"
        }

        // A barra só existe enquanto baixa: ela some quando termina, em vez de ficar
        // parada em 100% para sempre ocupando a linha.
        val f = item.fraction()
        if (item.status == DownloadStore.Status.RUNNING) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.isIndeterminate = f == null
            if (f != null) holder.progress.progress = (f * 1000).toInt()
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    private fun loadThumb(holder: VH, item: DownloadStore.Item) {
        val url = item.thumbnailUrl()
        if (url.isBlank()) return
        if (!loading.add(item.id)) return
        ThreadPool.postNetwork {
            val bmp = runCatching {
                URL(item.thumbnailUrl()).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) thumbs[item.id] = bmp
            ThreadPool.onUi {
                loading.remove(item.id)
                if (bmp == null || holder.boundId != item.id) return@onUi
                holder.thumb.setImageBitmap(bmp)
                holder.thumb.alpha = 1f
            }
        }
    }

    /**
     * Miniatura do PeerTube sem precisar guardar a URL: o próprio id é o UUID e a API
     * serve `/api/v1/videos/<uuid>/thumbnail.jpg` no mesmo host da página. Assim o item
     * baixado mostra o mesmo封面 da busca, e o JSON não carrega mais um campo.
     */
    private fun DownloadStore.Item.thumbnailUrl(): String {
        if (id.isBlank() || pageUrl.isBlank()) return ""
        val host = runCatching { URL(pageUrl).protocol + "://" + URL(pageUrl).host }.getOrNull()
            ?: return ""
        return "$host/api/v1/videos/$id/thumbnail.jpg"
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.dl_thumb)
        val play: ImageView = view.findViewById(R.id.dl_play)
        val title: TextView = view.findViewById(R.id.dl_title)
        val meta: TextView = view.findViewById(R.id.dl_meta)
        val progress: ProgressBar = view.findViewById(R.id.dl_progress)
        val menu: ImageView = view.findViewById(R.id.dl_menu)

        var boundId: String? = null
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
    }
}
