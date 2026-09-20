package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.pulsa.player.R
import com.pulsa.player.MainActivity
import com.pulsa.player.data.Library
import com.pulsa.player.model.Artist
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.dj.DjFacts
import com.pulsa.player.core.ThreadPool

class ArtistTimelineFragment : Fragment() {

    private var nameView: TextView? = null
    private var statsView: TextView? = null
    private var factsCard: MaterialCardView? = null
    private var factsView: TextView? = null
    private var timelineView: LinearLayout? = null
    private var showSongs: MaterialButton? = null
    private var artist: Artist? = null

    companion object {
        private const val ARG_NAME = "artist_name"
        private const val ARG_TRACKS = "artist_tracks"
        private const val ARG_ALBUMS = "artist_albums"

        fun artist(artist: Artist): ArtistTimelineFragment {
            return ArtistTimelineFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, artist.name)
                    putInt(ARG_TRACKS, artist.numSongs)
                    putInt(ARG_ALBUMS, artist.numAlbums)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_artist_timeline, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        nameView = view.findViewById(R.id.tl_name)
        statsView = view.findViewById(R.id.tl_stats)
        factsCard = view.findViewById(R.id.tl_facts_card)
        factsView = view.findViewById(R.id.tl_facts)
        timelineView = view.findViewById(R.id.tl_timeline)
        showSongs = view.findViewById(R.id.tl_show_songs)

        val args = arguments
        val name = args?.getString(ARG_NAME) ?: "Artista desconhecido"
        val tracks = args?.getInt(ARG_TRACKS) ?: 0
        val albums = args?.getInt(ARG_ALBUMS) ?: 0
        artist = Artist(name, tracks, albums)
        nameView?.text = name
        statsView?.text = getString(R.string.timeline_stats, albums, tracks)
        showSongs?.text = getString(R.string.timeline_show_songs, tracks)
        showSongs?.setOnClickListener {
            (activity as? MainActivity)?.openArtistLibrary(artist ?: return@setOnClickListener)
        }

        val fact = DjFacts.curiosityFor(name)
        if (fact != null) {
            factsView?.text = fact
            factsCard?.visibility = View.VISIBLE
        } else {
            factsView?.text = getString(R.string.timeline_today)
            factsCard?.visibility = View.VISIBLE
        }

        ThreadPool.post {
            val songs = Library.songsByArtist(requireActivity().applicationContext, name)
            ThreadPool.onUi {
                if (!isAdded) return@onUi
                renderTimeline(songs)
            }
        }
    }

    private fun renderTimeline(songs: List<Song>) {
        val host = timelineView ?: return
        host.removeAllViews()
        if (songs.isEmpty()) return

        val sorted = songs.sortedWith(compareByDescending<Song> { it.year }.thenBy { it.album.lowercase() })

        var lastYear = Int.MAX_VALUE
        var lastAlbum: String? = null
        for (song in sorted) {
            val year = if (song.year in 1900..2100) song.year else 0
            if (year != lastYear) {
                addHeader(host, yearText(year))
                lastYear = year
                lastAlbum = null
            }
            if (lastAlbum != song.album) {
                addAlbum(host, song.album)
                lastAlbum = song.album
            }
            addSongRow(host, song)
        }
    }

    private fun yearText(year: Int): String =
        if (year > 1900) year.toString() else getString(R.string.timeline_no_year)

    private fun addHeader(host: LinearLayout, label: String) {
        host.addView(TextView(requireContext()).apply {
            text = label.uppercase()
            setTextColor(color(R.color.text_secondary))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 18, 0, 4)
        })
    }

    private fun addAlbum(host: LinearLayout, album: String) {
        host.addView(TextView(requireContext()).apply {
            text = album
            setTextColor(themeColor())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 6, 0, 2)
        })
    }

    private fun addSongRow(host: LinearLayout, song: Song) {
        host.addView(TextView(requireContext()).apply {
            text = "•  ${song.title}"
            setTextColor(color(R.color.text_primary))
            textSize = 15f
            setPadding(12, 3, 12, 3)
            setOnClickListener { Playback.start(listOf(song), 0) }
        })
    }

    private fun color(res: Int): Int = ContextCompat.getColor(requireContext(), res)

    private fun themeColor(): Int =
        com.google.android.material.color.MaterialColors.getColor(
            requireContext(),
            com.google.android.material.R.attr.colorPrimary,
            R.color.primary
        )

    fun title(): String = getString(R.string.timeline_title)
}
