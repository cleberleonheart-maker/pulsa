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
import com.pulsa.player.data.Library
import com.pulsa.player.model.Artist
import com.pulsa.player.ui.adapter.ArtistListAdapter
import com.pulsa.player.util.Permissions
import com.pulsa.player.util.ThreadPool

class ArtistsTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var emptyAction: View? = null
    private var adapter: ArtistListAdapter? = null
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
        emptyAction = view.findViewById(R.id.empty_action)
        val a = ArtistListAdapter { artist -> openArtist(artist) }
        adapter = a
        view.findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = a
        }
        emptyAction?.setOnClickListener { Permissions.request(requireActivity()) }
    }

    override fun onResume() {
        super.onResume()
        load()
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
            val artists = Library.artists(ctx)
            ThreadPool.onUi {
                loading = false
                if (isAdded) {
                    headerContainer?.visibility = View.VISIBLE
                    headerTitle?.text = getString(R.string.tab_artists)
                    headerSubtitle?.text = if (artists.isEmpty()) "" else getString(R.string.artists_count, artists.size)
                    adapter?.artists = artists
                    if (artists.isEmpty()) {
                        showEmpty(getString(R.string.empty_no_music), false)
                    } else {
                        showEmpty(null, false)
                    }
                }
            }
        }
    }

    private fun openArtist(artist: Artist) {
        (activity as? MainActivity)?.openArtist(artist)
    }

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
        emptyAction?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun title(): String = requireContext().getString(R.string.tab_artists)
}