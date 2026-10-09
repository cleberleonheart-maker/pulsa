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
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.podcast.Episode
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale

/**
 * F3 — os episódios de um podcast.
 *
 * Três informações na linha, e todas as três mudam o que o usuário faz: **quando** saiu (é por
 * isso que o feed se chama lista), **quanto dura** (é o que faz o usuário decidir ouvir agora
 * ou depois) e **se já ouviu** (é o que separa uma fila de trabalho de um arquivo).
 *
 * A data vem do feed e muita vezes vem em UTC; o [Episode.publishedAt] já está em epoch, então
 * é formatado no fuso do aparelho — a lista mostra "hoje" para o que saiu hoje, que é como a
 * pessoa lê.
 */
class PodcastEpisodeAdapter(
    private val fallbackArt: String = "",
    private val onClick: (Episode) -> Unit,
    private val onMenu: (Episode) -> Unit
) : RecyclerView.Adapter<PodcastEpisodeAdapter.VH>() {

    var items: List<Episode> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** O que está tocando, para a linha não ficar igual às outras. */
    var playingId: Long = 0L
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    private val arts = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 40
    }
    private val loading = HashSet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_podcast_episode, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.episode_art)
        private val title: TextView = itemView.findViewById(R.id.episode_title)
        private val played: ImageView = itemView.findViewById(R.id.episode_played)
        private val meta: TextView = itemView.findViewById(R.id.episode_meta)
        private val menu: ImageView = itemView.findViewById(R.id.episode_menu)

        var boundGuid: String? = null

        fun bind(ep: Episode) {
            val ctx = itemView.context
            boundGuid = ep.guid

            title.text = ep.title.ifBlank { ctx.getString(R.string.podcast_episode) }
            played.visibility = if (ep.played) View.VISIBLE else View.GONE
            meta.text = metaOf(ctx, ep)

            val isPlaying = ep.id != 0L && ep.id == playingId
            itemView.alpha = if (ep.played && !isPlaying) 0.55f else 1f

            val artwork = ep.artworkUrl.takeIf { it.startsWith("https://") }
                ?: fallbackArt.takeIf { it.startsWith("https://") }
            art.setImageResource(R.drawable.ic_mic)
            art.alpha = 0.55f
            if (artwork != null) {
                arts[artwork]?.let { bmp ->
                    art.setImageBitmap(bmp)
                    art.alpha = 1f
                } ?: loadArt(ep, artwork)
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            menu.setOnClickListener { onMenu(ep) }
        }

        /** "05/10/2026 · 42:00 · faltam 18:00" — só o que o feed deu, sem inventar. */
        private fun metaOf(ctx: android.content.Context, ep: Episode): String {
            val parts = ArrayList<String>(4)
            if (ep.publishedAt > 0L) {
                parts += SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
                    .format(Date(ep.publishedAt))
            }
            if (ep.durationSec > 0L) {
                parts += Helper.formatDuration(ep.durationSec * 1000L)
                // "Faltam 18:00" é o que diz se vale apertar play agora. Sem isso o usuário ouve
                // o começo de um episódio de 40 min para descobrir que já tinha parado na
                // metade — e o `position_ms` existe justamente para não acontecer.
                val left = ep.durationSec * 1000L - ep.positionMs
                if (ep.positionMs > 0L && left > 0L && !ep.played) {
                    parts += ctx.getString(R.string.podcast_remaining, Helper.formatDuration(left))
                }
            }
            if (ep.filePath.isNotBlank()) parts += ctx.getString(R.string.podcast_downloaded)
            if (ep.isVideo) parts += ctx.getString(R.string.podcast_video)
            return parts.joinToString(" · ")
        }

        private fun loadArt(ep: Episode, url: String) {
            if (!loading.add(url)) return
            ThreadPool.post {
                val bmp = runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                    .getOrNull()
                if (bmp != null) arts[url] = bmp
                ThreadPool.onUi {
                    loading.remove(url)
                    if (bmp == null || boundGuid != ep.guid) return@onUi
                    art.setImageBitmap(bmp)
                    art.alpha = 1f
                }
            }
        }
    }
}