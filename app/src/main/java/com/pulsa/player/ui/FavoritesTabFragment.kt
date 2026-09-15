package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.R
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.util.ThreadPool

class FavoritesTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var list: RecyclerView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var adapter: SongListAdapter? = null
    private var emptyAction: View? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        list = view.findViewById(R.id.list)
        headerContainer = view.findViewById(R.id.header_container)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        emptyView = view.findViewById(R.id.empty_view)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        emptyAction?.visibility = View.GONE
        val a = SongListAdapter(
            onPlay = { song, pos ->
                adapter?.highlightId = song.id
                adapter?.let { Playback.start(it.songs, pos) }
            },
            onMenu = { song ->
                SongActions.show(requireContext(), song, onDeleted = { load() })
            }
        )
        adapter = a
        list?.apply {
            layoutManager = LinearLayoutManager(this@FavoritesTabFragment.context)
            adapter = a
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    fun load() {
        if (view == null) return
        if (loading) return
        loading = true
        ThreadPool.post {
            val songs = try {
                PlaylistDb.get(requireContext()).favorites()
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    headerContainer?.visibility = View.VISIBLE
                    headerTitle?.text = getString(R.string.tab_favorites)
                    headerSubtitle?.text = if (songs.isEmpty()) "" else getString(R.string.favorites_count, songs.size)
                    adapter?.songs = songs
                    adapter?.highlightId = Playback.currentSong?.id
                    val empty = songs.isEmpty()
                    emptyView?.visibility = if (empty) View.VISIBLE else View.GONE
                    emptyText?.text = getString(R.string.empty_favorites)
                    list?.visibility = if (empty) View.GONE else View.VISIBLE
                }
            }
        }
    }

    fun title(): String = requireContext().getString(R.string.tab_favorites)
}