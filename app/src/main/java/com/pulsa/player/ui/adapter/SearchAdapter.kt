package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.core.Helper
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.search.SearchResult

/**
 * F5 — A lista da busca unificada. Reaproveita a linha de música (`item_song`) porque a
 * informação é a mesma (arte, título, subtítulo, duração); o que muda é a ação do clique, que
 * quem decide é a [com.pulsa.player.ui.SearchActivity].
 */
class SearchAdapter(
    private val onClick: (SearchResult) -> Unit
) : RecyclerView.Adapter<SearchAdapter.VH>() {

    private val items = mutableListOf<SearchResult>()

    fun submit(list: List<SearchResult>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(view)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.song_art)
        private val title: TextView = itemView.findViewById(R.id.song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.song_subtitle)

        fun bind(r: SearchResult) {
            title.text = r.title
            val dur = if (r.durationMs > 0) " · " + Helper.formatDuration(r.durationMs) else ""
            subtitle.text = r.subtitle + dur
            itemView.findViewById<ImageView>(R.id.song_menu).visibility = View.GONE
            itemView.findViewById<ImageView>(R.id.song_check).visibility = View.GONE
            ArtLoader.load(r.albumId, r.artPath, art)
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }
}
