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
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.db.PodcastFeedRow
import java.net.URL
import java.util.LinkedHashMap

/**
 * F3 — as assinaturas, na aba Podcasts.
 *
 * A arte vem do feed e é remota, então a linha começa no `ic_mic` e troca quando o download
 * volta. O cache é por **endereço do feed** e não por id: reassinar o mesmo podcast não
 * invalida nada, e o id mudaria se a assinatura fosse desfeita e refeita.
 *
 * O `last_error` aparece **na linha**, e não num aviso que some: um feed que dá 404 continua
 * assinado e continua valendo para quando voltar, então a única forma de o usuário entender
 * por que a lista não cresce é ler o erro onde a lista é mostrada.
 */
class PodcastFeedAdapter(
    private val onClick: (PodcastFeedRow) -> Unit,
    private val onMenu: (PodcastFeedRow) -> Unit
) : RecyclerView.Adapter<PodcastFeedAdapter.VH>() {

    var items: List<PodcastFeedRow> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Capas por endereço de feed, com teto: a lista de assinaturas cresce devagar, mas cresce. */
    private val arts = object : LinkedHashMap<String, Bitmap>(0, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) = size > 30
    }
    private val loading = HashSet<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_podcast_feed, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.feed_art)
        private val title: TextView = itemView.findViewById(R.id.feed_title)
        private val author: TextView = itemView.findViewById(R.id.feed_author)
        private val meta: TextView = itemView.findViewById(R.id.feed_meta)
        private val error: TextView = itemView.findViewById(R.id.feed_error)
        private val menu: ImageView = itemView.findViewById(R.id.feed_menu)

        /** Endereço do feed exibido agora, para não aplicar capa vencida no recycler. */
        var boundUrl: String? = null

        fun bind(feed: PodcastFeedRow) {
            val ctx = itemView.context
            val url = feed.feedUrl.orEmpty()
            boundUrl = url

            title.text = feed.title?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.podcast)
            author.text = feed.author.orEmpty()
            author.visibility = if (feed.author.isNullOrBlank()) View.GONE else View.VISIBLE

            val unplayed = feed.unplayed ?: 0
            meta.text = if (unplayed > 0) {
                ctx.getString(
                    if (unplayed == 1) R.string.podcast_one_unplayed else R.string.podcast_n_unplayed,
                    unplayed
                )
            } else {
                ctx.getString(R.string.podcast_all_played)
            }

            val err = feed.lastError.orEmpty()
            error.text = err
            error.visibility = if (err.isEmpty()) View.GONE else View.VISIBLE

            art.setImageResource(R.drawable.ic_mic)
            art.alpha = 0.55f
            arts[url]?.let { bmp ->
                art.setImageBitmap(bmp)
                art.alpha = 1f
            } ?: loadArt(feed, url)

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
            menu.setOnClickListener { onMenu(feed) }
        }

        private fun loadArt(feed: PodcastFeedRow, url: String) {
            val artwork = feed.artworkUrl?.takeIf { it.startsWith("https://") } ?: return
            if (!loading.add(url)) return
            ThreadPool.post {
                val bmp = runCatching { URL(artwork).openStream().use { BitmapFactory.decodeStream(it) } }
                    .getOrNull()
                if (bmp != null) arts[url] = bmp
                ThreadPool.onUi {
                    loading.remove(url)
                    if (bmp == null || boundUrl != url) return@onUi
                    art.setImageBitmap(bmp)
                    art.alpha = 1f
                }
            }
        }
    }
}