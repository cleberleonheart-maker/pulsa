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
import com.pulsa.player.data.Library
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Playlist
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.util.Helper
import com.pulsa.player.util.ThreadPool

class PlaylistDetailFragment : Fragment() {

    private var playlistId: Long = -1L
    private var adapter: SongListAdapter? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        playlistId = arguments?.getLong(ARG_ID, -1L) ?: -1L
        val name = arguments?.getString(ARG_NAME) ?: ""
        val header = view.findViewById<View>(R.id.header_container)
        header.visibility = View.VISIBLE
        view.findViewById<ImageView>(R.id.header_art).visibility = View.GONE
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        headerTitle?.text = name
        val a = SongListAdapter(
            onPlay = { song, pos ->
                adapter?.highlightId = song.id
                adapter?.let { Playback.start(it.songs, pos) }
            },
            onMenu = { song ->
                SongActions.show(
                    requireContext(),
                    song,
                    R.string.remove_from_playlist to { confirmRemove(song) },
                    onDeleted = { load() }
                )
            }
        )
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
        view.findViewById<ImageView>(R.id.header_menu).apply {
            visibility = View.VISIBLE
            setOnClickListener { showAddSongs() }
        }
        view.findViewById<View>(R.id.empty_action).visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (view == null) return
        if (loading) return
        loading = true
        val ctx = requireContext().applicationContext
        val playlistId = this.playlistId
        ThreadPool.post {
            val db = PlaylistDb.get(ctx)
            try {
                if (db.isAutoAdd(playlistId)) {
                    db.syncAutoPlaylist(ctx, playlistId, Library.allSongs(ctx))
                }
            } catch (t: Throwable) {
            }
            val songs = try {
                db.songs(playlistId)
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    adapter?.songs = songs
                    adapter?.highlightId = Playback.currentSong?.id
                    if (db.isAutoAdd(playlistId)) {
                        headerSubtitle?.text = getString(R.string.auto_count, Helper.trackCount(songs.size, requireContext().resources))
                    } else {
                        headerSubtitle?.text = Helper.trackCount(songs.size, requireContext().resources)
                    }
                    showEmptyOrNot(songs.isEmpty())
                }
            }
        }
    }

    private fun confirmRemove(song: com.pulsa.player.model.Song) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(song.title)
            .setMessage(R.string.remove_song_confirm)
            .setPositiveButton(R.string.remove_from_playlist) { dialog, _ ->
                PlaylistDb.get(requireContext()).removeSong(playlistId, song.id)
                load()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAddSongs() {
        val ctx = requireContext()
        ThreadPool.post {
            val all = Library.allSongs(ctx)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                if (all.isEmpty()) return@onUi
                val titles = all.map { it.title }
                val checked = BooleanArray(all.size)
                androidx.appcompat.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.add_songs_title)
                    .setMultiChoiceItems(titles.toTypedArray(), checked) { _, _, _ -> }
                    .setPositiveButton(R.string.add_songs) { dialog, _ ->
                        var added = 0
                        val db = PlaylistDb.get(ctx)
                        all.forEachIndexed { i, song ->
                            if (checked[i] && db.addSong(playlistId, song)) added++
                        }
                        if (added > 0) {
                            android.widget.Toast.makeText(ctx, ctx.getString(R.string.songs_selected, added), android.widget.Toast.LENGTH_SHORT).show()
                        }
                        load()
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showEmptyOrNot(empty: Boolean) {
        view?.findViewById<View>(R.id.empty_view)?.visibility = if (empty) View.VISIBLE else View.GONE
        view?.findViewById<TextView>(R.id.empty_text)?.text = getString(R.string.empty_playlist)
    }

    fun title(): String = arguments?.getString(ARG_NAME) ?: ""

    fun reload() {
        load()
    }

    companion object {
        private const val ARG_ID = "id"
        private const val ARG_NAME = "name"

        fun forPlaylist(playlist: Playlist): PlaylistDetailFragment {
            return PlaylistDetailFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_ID, playlist.id)
                    putString(ARG_NAME, playlist.name)
                }
            }
        }
    }
}