package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.pulsa.player.MainActivity
import com.pulsa.player.R
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.model.Playlist
import com.pulsa.player.ui.adapter.PlaylistListAdapter
import com.pulsa.player.core.ThreadPool

class PlaylistsTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var adapter: PlaylistListAdapter? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        headerContainer = view.findViewById(R.id.header_container)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        emptyView = view.findViewById(R.id.empty_view)
        emptyText = view.findViewById(R.id.empty_text)
        val a = PlaylistListAdapter(
            onClick = { playlist -> (activity as? MainActivity)?.openPlaylist(playlist) },
            onMenu = { playlist ->
                if (!playlist.system) {
                    PlaylistDialog.showActions(requireContext(), playlist) { reload() }
                }
            }
        )
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        if (view == null) return
        if (loading) return
        loading = true
        ThreadPool.post {
            val playlists = try {
                PlaylistDb.get(requireContext()).playlists()
            } catch (t: Throwable) {
                emptyList()
            }
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    headerContainer?.visibility = View.VISIBLE
                    headerTitle?.text = getString(R.string.tab_playlists)
                    headerSubtitle?.text = if (playlists.isEmpty()) "" else getString(R.string.playlists_count, playlists.size)
                    adapter?.playlists = playlists
                    if (playlists.isEmpty()) {
                        showEmpty(getString(R.string.empty_playlist), false)
                    } else {
                        showEmpty(null, false)
                    }
                }
            }
        }
    }

    fun reload() {
        loading = false
        load()
    }

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
    }

    fun title(): String = requireContext().getString(R.string.tab_playlists)
}
