package com.pulsa.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.pulsa.player.R

/**
 * Aba "Biblioteca": hub com chips (Álbuns, Artistas, Favoritas, Playlists,
 * Vídeos) que trocam o fragmento interno — esqueleto novo, seções em vez de
 * uma tab deslizante de texto.
 */
class BibliotecaFragment : Fragment() {

    companion object {
        const val SECTION_ALBUMS = "albums"
        const val SECTION_ARTISTS = "artists"
        const val SECTION_FAVORITES = "favorites"
        const val SECTION_PLAYLISTS = "playlists"
        const val SECTION_VIDEOS = "videos"
        var pending = SECTION_ALBUMS
    }

    private val sections = mapOf(
        SECTION_ALBUMS to R.id.bibl_chip_albums,
        SECTION_ARTISTS to R.id.bibl_chip_artists,
        SECTION_FAVORITES to R.id.bibl_chip_favorites,
        SECTION_PLAYLISTS to R.id.bibl_chip_playlists,
        SECTION_VIDEOS to R.id.bibl_chip_videos
    )

    private var strip: ViewGroup? = null
    private var current = SECTION_ALBUMS

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_biblioteca, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val start = if (pending != SECTION_ALBUMS) pending else current
        renderChips()
        switchSection(start)
    }

    override fun onDestroyView() {
        strip = null
        super.onDestroyView()
    }

    private fun renderChips() {
        val v = view ?: return
        strip = v.findViewById(R.id.bibl_chip_strip)
        for ((key, chipId) in sections) {
            v.findViewById<View>(chipId).setOnClickListener { switchSection(key) }
        }
    }

    private fun switchSection(section: String) {
        if (!isAdded) return
        current = section
        val v = view ?: return
        for ((key, chipId) in sections) {
            v.findViewById<View>(chipId).isSelected = key == section
        }
        val fragment = when (section) {
            SECTION_ARTISTS -> ArtistsTabFragment()
            SECTION_FAVORITES -> FavoritesTabFragment()
            SECTION_PLAYLISTS -> PlaylistsTabFragment()
            SECTION_VIDEOS -> VideosTabFragment()
            else -> AlbumsTabFragment()
        }
        childFragmentManager.beginTransaction()
            .replace(R.id.bibl_container, fragment, section)
            .commit()
    }

    fun title(): String = getString(R.string.tab_biblioteca)
}