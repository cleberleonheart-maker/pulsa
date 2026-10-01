package com.pulsa.player.playback

import com.pulsa.player.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2b — a chave da fila tem que distinguir áudio de vídeo, e é isso que os testes medem.
 *
 * Sem Android: `Song` é um `data class` puro (veja [com.pulsa.player.dj.DjEngineTest], que
 * já instancia `Song` em JVM) e o `QueueKey` não toca em nada do framework.
 */
class QueueKeyTest {

    private fun audio(id: Long) = Song(
        id = id,
        title = "T$id",
        artist = "A",
        album = "X",
        albumId = id,
        durationMs = 200_000L,
        path = "/music/$id.mp3",
        year = 1990
    )

    private fun video(id: Long) = Song(
        id = id,
        title = "V$id",
        artist = "",
        album = "Videos",
        albumId = 0L,
        durationMs = 60_000L,
        path = Song.VIDEO_PREFIX + id,
        year = 0
    )

    private fun radio(url: String) = Song(
        id = url.hashCode().toLong() and 0x7fffffff,
        title = "Radio",
        artist = "Pop",
        album = "Radio",
        albumId = 0L,
        durationMs = 0L,
        path = Song.RADIO_PREFIX + url,
        year = 0
    )

    private fun stream(url: String) = Song(
        id = url.hashCode().toLong() and 0x7fffffffffffffffL,
        title = "Stream",
        artist = "",
        album = "Stream",
        albumId = 0L,
        durationMs = 0L,
        path = Song.STREAM_PREFIX + url,
        year = 0
    )

    // ------------------------------------------------------------------ encode

    @Test
    fun `audio e video com o mesmo id nao viram a mesma chave`() {
        // O ponto inteiro do QueueKey: o MediaStore numera as coleções por conta própria,
        // então id 42 de áudio e id 42 de vídeo coexistem.
        val a = QueueKey.encode(audio(42))
        val v = QueueKey.encode(video(42))
        assertEquals("a:42", a)
        assertEquals("v:42", v)
        assertFalse(QueueKey.sameType(a, v))
    }

    @Test
    fun `radio e stream nao sao retomaveis`() {
        // Os dois usam id sintetico e nao tem item no MediaStore para o restore reencontrar.
        assertNull(QueueKey.encode(radio("https://radio.example/live")))
        assertNull(QueueKey.encode(stream("https://cdn.example/master.m3u8")))
    }

    @Test
    fun `video sem id utilizavel nao vira chave`() {
        // `video:notANumber` — o videoId é null e a chave seria "v:null".
        val broken = audio(1).copy(path = Song.VIDEO_PREFIX + "notANumber")
        assertNull(QueueKey.encode(broken))
    }

    @Test
    fun `encodeAll pula o que nao e retomavel e mantem a ordem`() {
        val songs = listOf(audio(1), radio("https://r/1"), video(2), stream("https://s/1"), audio(3))
        assertEquals(listOf("a:1", "v:2", "a:3"), QueueKey.encodeAll(songs))
    }

    // ------------------------------------------------------------------ decode

    @Test
    fun `decode le a chave nova`() {
        assertEquals("a:42", QueueKey.decode("a:42"))
        assertEquals("v:7", QueueKey.decode("v:7"))
    }

    @Test
    fun `decode le o formato antigo, que era so o id`() {
        // Fila gravada por uma versao anterior do app: `queue_ids` era um JSONArray de
        // numeros. Tudo era audio, entao o numero solto vira chave de audio.
        assertEquals("a:42", QueueKey.decode("42"))
    }

    @Test
    fun `decode recusa o que nao e chave`() {
        assertNull(QueueKey.decode(""))
        assertNull(QueueKey.decode("   "))
        assertNull(QueueKey.decode("abc"))
        assertNull(QueueKey.decode("x:1"))
        assertNull(QueueKey.decode("a:notANumber"))
        assertNull(QueueKey.decode("a:"))
    }

    @Test
    fun `decode normaliza espacos`() {
        assertEquals("a:42", QueueKey.decode(" a:42 "))
        assertEquals("v:7", QueueKey.decode("v: 7"))
    }

    // ------------------------------------------------------------------ reanchor

    @Test
    fun `reanchor acha a mesma faixa mesmo com id repetido em outra midia`() {
        // Salvo: a:1, a:2, v:2 (o vídeo 2 estava tocando). Restaurado veio a:1, v:2 — o
        // audio 2 sumiu. Um indexOf por id pegaria o a:2 que nao esta mais, ou pior,
        // pegaria o v:2 como se fosse o a:2.
        val at = QueueKey.reanchor(
            savedKeys = listOf("a:1", "a:2", "v:2"),
            savedIndex = 2,
            loadedKeys = listOf("a:1", "v:2")
        )
        assertEquals(1, at)
    }

    @Test
    fun `reanchor volta para o inicio quando a faixa atual nao voltou`() {
        val at = QueueKey.reanchor(
            savedKeys = listOf("a:1", "a:2", "a:3"),
            savedIndex = 2,
            loadedKeys = listOf("a:1", "a:2")
        )
        assertEquals(0, at)
    }

    @Test
    fun `reanchor devolve menos zero quando nada voltou`() {
        // O caller usa esse -1 para nao tentar tocar fila vazia nem limpar o estado salvo
        // antes de saber que a fila morreu.
        assertEquals(-1, QueueKey.reanchor(listOf("a:1"), 0, emptyList()))
    }

    @Test
    fun `reanchor sobrevive a indice fora da faixa salva`() {
        // Índice corrompido nas prefs (restore de versao antiga, edicao manual): nao pode
        // estourar nem devolver a faixa errada silenciosamente.
        val at = QueueKey.reanchor(listOf("a:1", "a:2"), 99, listOf("a:1", "a:2"))
        assertEquals(1, at)
        val negative = QueueKey.reanchor(listOf("a:1", "a:2"), -5, listOf("a:1", "a:2"))
        assertEquals(0, negative)
    }

    @Test
    fun `ida e volta preserva a chave`() {
        val songs = listOf(audio(11), video(22))
        val keys = QueueKey.encodeAll(songs)
        assertEquals(keys, keys.mapNotNull { QueueKey.decode(it) })
    }

    @Test
    fun `sameType ignora null e nao devolve true para dois nulos`() {
        // `at == index` e o guarda contra laco no adoptPlayerIndex; dois nulos como iguais
        // fariam a comparacao dizer "mesma faixa" sem haver faixa nenhuma.
        assertFalse(QueueKey.sameType(null, null))
        assertFalse(QueueKey.sameType("a:1", null))
        assertFalse(QueueKey.sameType(null, "a:1"))
        assertTrue(QueueKey.sameType("a:1", "a:1"))
    }
}