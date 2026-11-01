package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastAutoDownloadTest {

    private fun ep(
        id: Long,
        feed: Long = 1L,
        published: Long = 0L,
        video: Boolean = false,
        downloaded: Boolean = false,
        url: String = "https://cdn.example/ep$id.mp3"
    ) = Episode(
        id = id,
        podcastId = feed,
        guid = "g$id",
        title = "Episódio $id",
        audioUrl = url,
        publishedAt = published,
        isVideo = video,
        filePath = if (downloaded) "content://media/ep$id" else ""
    )

    @Test
    fun escolhe_os_mais_recentes_do_feed() {
        val escolhidos = PodcastAutoDownload.select(
            listOf(
                ep(1, published = 100),
                ep(2, published = 300),
                ep(3, published = 200)
            ),
            limit = 2
        )

        assertEquals(listOf(2L, 3L), escolhidos.map { it.id })
    }

    @Test
    fun limite_zero_desliga_tudo() {
        val escolhidos = PodcastAutoDownload.choose(
            listOf(ep(1, published = 100)),
            limit = 0
        )

        assertTrue(escolhidos.isEmpty())
    }

    @Test
    fun ignora_video_baixado_e_url_que_nao_e_http() {
        val escolhidos = PodcastAutoDownload.select(
            listOf(
                ep(1, published = 400),
                ep(2, published = 300, video = true),
                ep(3, published = 200, downloaded = true),
                ep(4, published = 100, url = "ftp://cdn.example/ep4.mp3"),
                ep(5, published = 50, url = "podcast:5")
            ),
            limit = 5
        )

        assertEquals(listOf(1L), escolhidos.map { it.id })
    }

    @Test
    fun aplica_o_limite_por_feed_e_nao_no_total() {
        val escolhidos = PodcastAutoDownload.choose(
            listOf(
                ep(1, feed = 1L, published = 100),
                ep(2, feed = 1L, published = 300),
                ep(3, feed = 1L, published = 200),
                ep(4, feed = 2L, published = 400),
                ep(5, feed = 2L, published = 600),
                ep(6, feed = 2L, published = 500)
            ),
            limit = 1
        )

        assertEquals(2, escolhidos.size)
        assertEquals(setOf(2L, 5L), escolhidos.map { it.id }.toSet())
    }
}
