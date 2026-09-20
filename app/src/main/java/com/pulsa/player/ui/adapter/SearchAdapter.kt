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
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song
import com.pulsa.player.core.Helper

sealed class SearchResult {
    data class Header(val label: String) : SearchResult()
    data class SongResult(val song: Song) : SearchResult()
    data class AlbumResult(val album: Album) : SearchResult()
    data class ArtistResult(val artist: Artist) : SearchResult()
}

class SearchAdapter(
    private val onSong: (List<Song>, Int) -> Unit,
    private val onAlbum: (Album) -> Unit,
    private val onArtist: (Artist) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var items: List<SearchResult> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is SearchResult.Header -> TYPE_HEADER
            is SearchResult.SongResult -> TYPE_SONG
            is SearchResult.AlbumResult -> TYPE_ALBUM
            is SearchResult.ArtistResult -> TYPE_ARTIST
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_search_header, parent, false))
            else -> RowVH(inflater.inflate(R.layout.item_search_result, parent, false))
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SearchResult.Header -> (holder as HeaderVH).bind(item)
            is SearchResult.SongResult -> (holder as RowVH).bindSong(item.song)
            is SearchResult.AlbumResult -> (holder as RowVH).bindAlbum(item.album)
            is SearchResult.ArtistResult -> (holder as RowVH).bindArtist(item.artist)
        }
    }

    inner class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val label: TextView = itemView as TextView
        fun bind(item: SearchResult.Header) {
            label.text = item.label
        }
    }

    inner class RowVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val art: ImageView = itemView.findViewById(R.id.result_art)
        private val title: TextView = itemView.findViewById(R.id.result_title)
        private val subtitle: TextView = itemView.findViewById(R.id.result_subtitle)

        fun bindSong(song: Song) {
            ArtLoader.load(song.albumId, song.path, art)
            title.text = song.title
            subtitle.text = song.artist + " · " + song.album
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    val songs = items.filterIsInstance<SearchResult.SongResult>().map { it.song }
                    val offset = songs.indexOf(song)
                    onSong(songs, offset.coerceAtLeast(0))
                }
            }
        }

        fun bindAlbum(album: Album) {
            ArtLoader.load(album.id, "", art)
            title.text = album.name
            subtitle.text = album.artist + " · " + Helper.trackCount(album.numSongs, itemView.context.resources)
            itemView.setOnClickListener { onAlbum(album) }
        }

        fun bindArtist(artist: Artist) {
            art.setImageResource(R.drawable.ic_music_note)
            art.background = itemView.context.getDrawable(R.drawable.bg_circle)
            title.text = artist.name
            subtitle.text = Helper.trackCount(artist.numSongs, itemView.context.resources)
            itemView.setOnClickListener { onArtist(artist) }
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_SONG = 1
        private const val TYPE_ALBUM = 2
        private const val TYPE_ARTIST = 3
    }
}
