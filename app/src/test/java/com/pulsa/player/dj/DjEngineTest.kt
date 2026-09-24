package com.pulsa.player.dj

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DjEngineTest {

    private fun song(id: Long, artist: String = "A", album: String = "X"): Song =
        Song(id, "T$id", artist, album, id, 200_000L, "/s$id.mp3", year = 1990)

    private val pool = listOf(
        song(1), song(2), song(3), song(4), song(5), song(6), song(7), song(8), song(9), song(10)
    )

    @Test
    fun disliked_and_avoided_are_not_picked_when_pool_is_large() {
        val learn = DjEngine.Learn(
            disliked = setOf(1L),
            avoided = setOf(2L)
        )
        val out = DjEngine.build(
            pool, emptySet(), DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn
        )
        val ids = out.map { it.id }
        assertTrue(1L !in ids)
        assertTrue(2L !in ids)
    }

    @Test
    fun avoided_still_fills_when_library_is_tiny() {
        val tiny = listOf(song(1), song(2))
        val learn = DjEngine.Learn(avoided = setOf(2L))
        val out = DjEngine.build(
            tiny, emptySet(), DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn, maxSize = 1
        )
        assertEquals(1, out.size)
        assertEquals(1L, out.first().id)
    }

    @Test
    fun plain_skip_counters_do_not_hide_songs_by_default() {
        val learn = DjEngine.Learn(skipCount = mapOf(3L to 2, 4L to 4))
        val seen = HashSet<Long>()
        repeat(20) {
            val out = DjEngine.build(
                pool, emptySet(), DjEngine.Source.ALL, DjEngine.Intensity.BALANCED, learn
            )
            seen += out.map { it.id }
        }
        assertTrue(3L in seen)
        assertTrue(4L in seen)
    }
}