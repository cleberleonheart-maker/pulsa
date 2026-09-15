package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.model.Album
import com.pulsa.player.util.Helper

class AlbumGridAdapter(
    private val onClick: (Album) -> Unit
) : RecyclerView.Adapter<AlbumGridAdapter.VH>() {

    var albums: List<Album> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = albums.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(albums[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.album_art)
        private val name: TextView = itemView.findViewById(R.id.album_name)
        private val artist: TextView = itemView.findViewById(R.id.album_artist)

        fun bind(album: Album) {
            name.text = album.name
            artist.text = album.artist + " · " + Helper.trackCount(album.numSongs, itemView.context.resources)
            ArtLoader.load(album.id, "", art)
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(albums[pos])
            }
        }
    }
}