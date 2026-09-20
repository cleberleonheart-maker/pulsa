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
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.SongListAdapter
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Permissions
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

class SongsTabFragment : Fragment() {

    private var headerContainer: View? = null
    private var headerTitle: TextView? = null
    private var headerSubtitle: TextView? = null
    private var list: RecyclerView? = null
    private var emptyView: View? = null
    private var emptyText: TextView? = null
    private var emptyAction: View? = null
    private var adapter: SongListAdapter? = null
    private var loading = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        headerContainer = view.findViewById(R.id.header_container)
        headerTitle = view.findViewById(R.id.header_title)
        headerSubtitle = view.findViewById(R.id.header_subtitle)
        list = view.findViewById(R.id.list)
        emptyView = view.findViewById(R.id.empty_view)
        emptyText = view.findViewById(R.id.empty_text)
        emptyAction = view.findViewById(R.id.empty_action)
        val a = SongListAdapter(
            onPlay = { song, pos ->
                adapter?.highlightId = song.id
                adapter?.let { Playback.start(it.songs, pos) }
            },
            onMenu = { song -> SongActions.show(requireContext(), song, onDeleted = { load() }) }
        )
        adapter = a
        list?.apply {
            layoutManager = LinearLayoutManager(this@SongsTabFragment.context)
            adapter = a
        }
        emptyAction?.setOnClickListener { Permissions.request(requireActivity()) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    fun load() {
        if (view == null) return
        val ctx = requireContext()
        if (!Permissions.hasAccess(ctx)) {
            CrashLogger.writeLog(ctx, "MARK: Songs sem permissao -> empty")
            showEmpty(getString(R.string.empty_no_permission), true)
            return
        }
        if (loading) return
        loading = true
        ThreadPool.post {
            val songs = try {
                Library.allSongs(ctx)
            } catch (t: Throwable) {
                emptyList()
            }
            val ordered = if (Settings.sortAlphabetical(ctx)) {
                songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            } else {
                songs
            }
            ThreadPool.onUi {
                CrashLogger.writeLog(ctx, "MARK: Songs carregados=" + ordered.size)
                loading = false
                if (isAdded) {
                    headerContainer?.visibility = View.VISIBLE
                    headerTitle?.text = getString(R.string.tab_songs)
                    headerSubtitle?.text = getString(R.string.songs_count, ordered.size)
                    adapter?.songs = ordered
                    adapter?.highlightId = Playback.currentSong?.id
                    if (ordered.isEmpty()) {
                        showEmpty(getString(R.string.empty_no_music), false)
                    } else {
                        showEmpty(null, false)
                    }
                }
            }
        }
    }

    private fun showEmpty(text: String?, showAction: Boolean) {
        emptyText?.text = text
        emptyView?.visibility = if (text != null) View.VISIBLE else View.GONE
        emptyAction?.visibility = if (showAction) View.VISIBLE else View.GONE
    }

    fun title(): String = requireContext().getString(R.string.tab_songs)
}
