package com.pulsa.player.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PeerTubeTest {

    @Test
    fun busca_traz_a_descricao_que_vira_capitulo() {
        val obj = JSONObject()
            .put("uuid", "abc123")
            .put("name", "Documentário")
            .put("url", "https://peertube.tv/videos/watch/abc123")
            .put("duration", 3600)
            .put("description", "00:00 Abertura\n12:30 O assunto\n40:00 Fim")

        val item = PeerTube.fromSearch(obj, "https://peertube.tv")

        assertNotNull(item)
        assertEquals("00:00 Abertura\n12:30 O assunto\n40:00 Fim", item!!.description)
    }

    @Test
    fun busca_sem_descricao_devolve_texto_vazio() {
        val obj = JSONObject()
            .put("uuid", "abc123")
            .put("name", "Sem descrição")
            .put("url", "https://peertube.tv/videos/watch/abc123")

        val item = PeerTube.fromSearch(obj, "https://peertube.tv")

        assertEquals("", item!!.description)
    }

    @Test
    fun sem_uuid_nao_e_item() {
        val obj = JSONObject().put("name", "Quebrado")
        assertNull(PeerTube.fromSearch(obj, "https://peertube.tv"))
    }
}
