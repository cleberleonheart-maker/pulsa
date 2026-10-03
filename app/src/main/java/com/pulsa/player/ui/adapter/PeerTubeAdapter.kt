package com.pulsa.player.ui.adapter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.PeerTube
import com.pulsa.player.core.ThreadPool
import java.net.URL
import java.util.LinkedHashMap

/**
 * F2 — resultados da busca no PeerTube.
 *
 * Reaproveita o `item_video.xml` (mesmos ids) em vez de um layout novo: a linha é a mesma da
 * biblioteca, muda só a origem do stream. O item mostra se é HLS ou MP4 porque o usuário
 * escolheu "tocar vídeo", e saber se vai ter troca automática de qualidade é informação
 * útil, não detalhe.
 */
class PeerTubeAdapter(
    private val onClick: (PeerTube.Item) -> Unit,
    private val onMenu: (PeerTube.Item) -> Unit
) : RecyclerView.Adapter<PeerTubeAdapter.VH>() {

    var items: List<PeerTube.Item> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Thumbnails por UUID, com teto de tamanho, para não segurar a lista inteira em memória. */
    private val thumbs = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }
    private val loading = HashSet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context

        holder.boundUuid = item.uuid
        holder.title.text = item.title

        holder.thumb.setImageResource(R.drawable.ic_videocam)
        holder.thumb.alpha = 0.55f
        thumbs[item.uuid]?.let { bmp ->
            holder.thumb.setImageBitmap(bmp)
            holder.thumb.alpha = 1f
        } ?: loadThumb(holder, item)

        val minutes = item.durationSec / 60
        val seconds = item.durationSec % 60
        val dur = if (item.durationSec > 0) String.format("%d:%02d", minutes, seconds) else "?"
        // HLS/MP4 **não** aparece aqui: a busca não devolve stream (ver PeerTube), ele só é
        // resolvido no clique. Mostrar o formato aqui seria mentir — a lista não sabe.
        val host = item.pageUrl.removePrefix("https://").removePrefix("http://")
            .substringBefore("/videos/watch")
        holder.meta.text = ctx.getString(R.string.peertube_meta, dur, host)

        holder.itemView.setOnClickListener { onClick(item) }
        holder.menu.setOnClickListener { onMenu(item) }
    }

    private fun loadThumb(holder: VH, item: PeerTube.Item) {
        val url = item.thumbnail ?: return
        if (!loading.add(item.uuid)) return
        ThreadPool.post {
            val bmp = runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                .getOrNull()
            if (bmp != null) thumbs[item.uuid] = bmp
            ThreadPool.onUi {
                loading.remove(item.uuid)
                // O holder pode já estar recycler pra outro item: só aplica se for o mesmo.
                if (bmp == null || holder.boundUuid != item.uuid) return@onUi
                holder.thumb.setImageBitmap(bmp)
                holder.thumb.alpha = 1f
            }
        }
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.video_thumb)
        val title: TextView = view.findViewById(R.id.video_title)
        val meta: TextView = view.findViewById(R.id.video_meta)
        val menu: ImageView = view.findViewById(R.id.video_menu)

        /** UUID do item que este holder está exibindo agora (para validar thumb async). */
        var boundUuid: String? = null
    }
}
