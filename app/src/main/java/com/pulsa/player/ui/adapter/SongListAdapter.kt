package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.model.Song
import com.pulsa.player.core.Helper

class SongListAdapter(
    private val onPlay: (Song, Int) -> Unit,
    private val onMenu: (Song) -> Unit
) : RecyclerView.Adapter<SongListAdapter.VH>() {

    var songs: List<Song> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var highlightId: Long? = null
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = songs.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(songs[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.song_art)
        private val title: TextView = itemView.findViewById(R.id.song_title)
        private val subtitle: TextView = itemView.findViewById(R.id.song_subtitle)
        private val menu: ImageView = itemView.findViewById(R.id.song_menu)

        fun bind(song: Song) {
            title.text = song.title
            subtitle.text = song.artist + " · " + Helper.formatDuration(song.durationMs)
            ArtLoader.load(song.albumId, song.path, art)

            val selected = song.id == highlightId
            itemView.setBackgroundResource(
                if (selected) R.drawable.bg_song_selected else R.drawable.bg_song_normal
            )
            title.alpha = if (selected) 1f else 0.75f

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    highlightId = song.id
                    onPlay(song, pos)
                }
            }
            menu.setOnClickListener { onMenu(song) }
        }
    }
}
