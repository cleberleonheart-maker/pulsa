package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.pulsa.player.R
import com.pulsa.player.data.Library
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.core.ThreadPool

class TrendsFragment : Fragment() {

    private var heatmap: HeatmapView? = null
    private var songsHost: LinearLayout? = null
    private var artistsHost: LinearLayout? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_trends, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        heatmap = view.findViewById(R.id.tr_heatmap)
        songsHost = view.findViewById(R.id.tr_songs)
        artistsHost = view.findViewById(R.id.tr_artists)
        val accent = com.google.android.material.color.MaterialColors.getColor(
            requireContext(), com.google.android.material.R.attr.colorPrimary, R.color.primary
        )
        heatmap?.setAccent(accent, ContextCompat.getColor(requireContext(), R.color.neon_border_soft))
        ThreadPool.post {
            val app = requireActivity().applicationContext
            val data = DjLearn.heatmap(app)
            val songs = Library.allSongs(app)
            val byId = HashMap<Long, Song>().apply { songs.forEach { put(it.id, it) } }
            val top = DjLearn.topSongs(app, 7, 10)
            val ranked = top.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                heatmap?.setData(data)
                if (data.all { it == 0 }) {
                    songsHost?.let { host ->
                        host.removeAllViews()
                        host.addView(emptyText())
                    }
                    artistsHost?.removeAllViews()
                    return@onUi
                }
                renderSongs(ranked)
                renderArtists(ranked)
            }
        }
    }

    private fun renderSongs(ranked: List<Pair<Song, Int>>) {
        val host = songsHost ?: return
        host.removeAllViews()
        if (ranked.isEmpty()) {
            host.addView(emptyText())
            return
        }
        ranked.forEachIndexed { index, (song, plays) ->
            host.addView(TextView(requireContext()).apply {
                text = "${index + 1}. ${song.title} — ${song.artist}   ${getString(R.string.trends_plays, plays)}"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 14f
                setPadding(0, 6, 0, 6)
                setOnClickListener {
                    Playback.start(ranked.map { it.first }, index)
                }
            })
        }
    }

    private fun renderArtists(ranked: List<Pair<Song, Int>>) {
        val host = artistsHost ?: return
        host.removeAllViews()
        val perArtist = LinkedHashMap<String, Int>()
        for ((song, plays) in ranked) {
            perArtist[song.artist] = (perArtist[song.artist] ?: 0) + plays
        }
        val ordered = perArtist.entries.sortedByDescending { it.value }
        ordered.forEach { (artist, plays) ->
            host.addView(TextView(requireContext()).apply {
                text = "${artist}   ${getString(R.string.trends_plays, plays)}"
                setTextColor(themeColor())
                textSize = 14f
                setPadding(0, 6, 0, 6)
            })
        }
    }

    private fun emptyText(): TextView = TextView(requireContext()).apply {
        text = getString(R.string.trends_no_data)
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        textSize = 13f
        setPadding(0, 10, 0, 10)
    }

    private fun themeColor(): Int =
        com.google.android.material.color.MaterialColors.getColor(
            requireContext(),
            com.google.android.material.R.attr.colorPrimary,
            R.color.primary
        )

    fun title(): String = getString(R.string.trends_title)
}
