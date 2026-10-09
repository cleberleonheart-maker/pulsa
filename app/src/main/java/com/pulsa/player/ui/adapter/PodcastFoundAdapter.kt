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
import com.pulsa.player.podcast.PodcastNet
import java.net.URL
import java.util.LinkedHashMap

/**
 * F3 — resultado da busca de podcast.
 *
 * Reaproveita o `item_podcast_feed.xml` das assinaturas (mesmos ids) e **não** mostra a contagem
 * de não ouvido: a busca devolve um podcast que o usuário ainda não assinou, então "0 não
 * ouvido" seria mentira. O que aparece é o gênero, que é o que ajuda a escolher.
 *
 * Não tem menu: na busca a única ação é assinar, e um `⋮` sem nada dentro seria ruído.
 */
class PodcastFoundAdapter(
    private val onClick: (PodcastNet.Found) -> Unit
) : RecyclerView.Adapter<PodcastFoundAdapter.VH>() {

    var items: List<PodcastNet.Found> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

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

        var boundUrl: String? = null

        fun bind(found: PodcastNet.Found) {
            val ctx = itemView.context
            boundUrl = found.feedUrl

            title.text = found.title
            author.text = found.author
            author.visibility = if (found.author.isBlank()) View.GONE else View.VISIBLE
            meta.text = found.genre
            meta.visibility = if (found.genre.isBlank()) View.GONE else View.VISIBLE
            error.visibility = View.GONE
            menu.visibility = View.GONE

            art.setImageResource(R.drawable.ic_mic)
            art.alpha = 0.55f
            if (found.artworkUrl.startsWith("https://")) {
                arts[found.artworkUrl]?.let { bmp ->
                    art.setImageBitmap(bmp)
                    art.alpha = 1f
                } ?: loadArt(found)
            }

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }

        private fun loadArt(found: PodcastNet.Found) {
            val url = found.artworkUrl
            if (!loading.add(url)) return
            ThreadPool.post {
                val bmp = runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }
                    .getOrNull()
                if (bmp != null) arts[url] = bmp
                ThreadPool.onUi {
                    loading.remove(url)
                    if (bmp == null || boundUrl != found.feedUrl) return@onUi
                    art.setImageBitmap(bmp)
                    art.alpha = 1f
                }
            }
        }
    }
}