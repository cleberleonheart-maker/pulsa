package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.model.Artist
import com.pulsa.player.core.Helper

class ArtistListAdapter(
    private val onClick: (Artist) -> Unit
) : RecyclerView.Adapter<ArtistListAdapter.VH>() {

    var artists: List<Artist> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = artists.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(artists[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewById(R.id.artist_initial)
        private val name: TextView = itemView.findViewById(R.id.artist_name)
        private val tracks: TextView = itemView.findViewById(R.id.artist_tracks)

        fun bind(artist: Artist) {
            name.text = artist.name
            tracks.text = Helper.trackCount(artist.numSongs, itemView.context.resources)
            initial.text = artist.name.trim().firstOrNull()?.uppercase() ?: "?"
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(artists[pos])
            }
        }
    }
}
