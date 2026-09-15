package com.pulsa.player.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.model.Playlist
import com.pulsa.player.util.Helper

class PlaylistListAdapter(
    private val onClick: (Playlist) -> Unit,
    private val onMenu: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistListAdapter.VH>() {

    var playlists: List<Playlist> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_playlist, parent, false)
        return VH(view)
    }

    override fun getItemCount(): Int = playlists.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(playlists[position])
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val name: TextView = itemView.findViewById(R.id.playlist_name)
        private val count: TextView = itemView.findViewById(R.id.playlist_count)
        private val menu: ImageView = itemView.findViewById(R.id.playlist_menu)

        fun bind(playlist: Playlist) {
            name.text = playlist.name
            if (playlist.autoAdd) {
                count.text = itemView.context.getString(R.string.auto_count, Helper.trackCount(playlist.count, itemView.context.resources))
            } else {
                count.text = Helper.trackCount(playlist.count, itemView.context.resources)
            }
            menu.visibility = if (playlist.system) View.GONE else View.VISIBLE
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onClick(playlists[pos])
            }
            menu.setOnClickListener { onMenu(playlist) }
        }
    }
}