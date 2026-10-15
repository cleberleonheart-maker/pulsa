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

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.song_art)
        private val title: TextView = itemView.findViewById(R.id.song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.song_subtitle)
        private val menu: ImageView = itemView.findViewById(R.id.song_menu)
        private val check: ImageView = itemView.findViewById(R.id.song_check)

        fun bind(r: SearchResult) {
            title.text = r.title
            val dur = if (r.durationMs > 0) " · ${Helper.formatDuration(r.durationMs)}" else ""
            subtitle.text = r.subtitle + dur
            if (r.albumId > 0) {
                ArtLoader.load(r.albumId, null, art)
            } else {
                art.setImageResource(R.drawable.ic_music_note)
            }
            menu.visibility = View.GONE
            check.visibility = View.GONE
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(items[pos])
            }
        }
    }
}
