package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.ArtLoader
import com.pulsa.player.data.Library
import com.pulsa.player.model.Album
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.util.Permissions
import com.pulsa.player.util.ThreadPool

class LibraryDetailFragment : Fragment() {

    private var adapter: SongListAdapter? = null
    private var header: View? = null
    private var headerArt: ImageView? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        header = view.findViewById(R.id.header_container)
        headerArt = view.findViewById(R.id.header_art)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        val a = SongListAdapter(
            onPlay = { song, pos ->
                adapter?.highlightId = song.id
                adapter?.let { Playback.start(it.songs, pos) }
            },
            onMenu = { song -> SongActions.show(requireContext(), song, onDeleted = { load() }) }
        )
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
        view.findViewById<View>(R.id.empty_action).setOnClickListener {
            Permissions.request(requireActivity())
        }
        view.findViewById<View>(R.id.header_menu).visibility = View.GONE
        setupHeader()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun setupHeader() {
        val type = arguments?.getString(ARG_TYPE)
        val title = arguments?.getString(ARG_TITLE) ?: ""
        headerTitle?.text = title
        header?.visibility = View.VISIBLE
        if (type == TYPE_ALBUM) {
            val albumId = arguments?.getLong(ARG_ALBUM_ID, -1L) ?: -1L
            headerSubtitle?.text = arguments?.getString(ARG_SUBTITLE)
            ArtLoader.load(albumId, "", headerArt ?: return)
        } else {
            val count = arguments?.getInt(ARG_SONG_COUNT, 0) ?: 0
            headerSubtitle?.text = com.pulsa.player.util.Helper.trackCount(count, requireContext().resources)
            headerArt?.setImageResource(R.drawable.ic_person)
        }
    }

    private fun load() {
        if (view == null) return
        val ctx = requireContext()
        if (!Permissions.hasAccess(ctx)) {
            showEmpty(getString(R.string.empty_no_permission), true)
            return
        }
        if (loading) return
        loading = true
        ThreadPool.post {
            val type = arguments?.getString(ARG_TYPE)
            val songs = if (type == TYPE_ALBUM) {
                Library.songsByAlbum(ctx, arguments?.getLong(ARG_ALBUM_ID, -1L) ?: -1L)
            } else {
                Library.songsByArtist(ctx, arguments?.getString(ARG_TITLE) ?: "")
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    adapter?.songs = songs
                    adapter?.highlightId = Playback.currentSong?.id
                    headerSubtitle?.text = subtitleFor(songs)
                    if (songs.isEmpty()) showEmpty(getString(R.string.empty_no_music), false)
                    else showEmpty(null, false)
                }
            }
        }
    }

    private fun subtitleFor(songs: List<Song>): String {
        val base = arguments?.getString(ARG_SUBTITLE) ?: ""
        val count = com.pulsa.player.util.Helper.trackCount(songs.size, requireContext().resources)
        return if (base.isNullOrBlank()) count else "$base · $count"
    }

    private fun showEmpty(text: String?, showAction: Boolean) {
        view?.findViewById<TextView>(R.id.empty_text)?.text = text
        view?.findViewById<View>(R.id.empty_view)?.visibility = if (text != null) View.VISIBLE else View.GONE
        view?.findViewById<View>(R.id.empty_action)?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun title(): String = arguments?.getString(ARG_TITLE) ?: ""

    fun reload() {
        load()
    }

    companion object {
        private const val TYPE_ALBUM = "album"
        private const val ARG_TYPE = "type"
        private const val ARG_ALBUM_ID = "album_id"
        private const val ARG_TITLE = "title"
        private const val ARG_SUBTITLE = "subtitle"
        private const val ARG_SONG_COUNT = "song_count"

        fun album(album: Album): LibraryDetailFragment {
            return LibraryDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, TYPE_ALBUM)
                    putLong(ARG_ALBUM_ID, album.id)
                    putString(ARG_TITLE, album.name)
                    putString(ARG_SUBTITLE, album.artist)
                }
            }
        }

        fun artist(artist: Artist): LibraryDetailFragment {
            return LibraryDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TYPE, "artist")
                    putString(ARG_TITLE, artist.name)
                    putInt(ARG_SONG_COUNT, artist.numSongs)
                }
            }
        }
    }
}