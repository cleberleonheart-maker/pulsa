package com.pulsa.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProtocolTest {

    @Test
    fun corpo_vazio_fica_offline() {
        val snap = RemoteProtocol.parseSession("")
        assertFalse(snap.ok)
        assertFalse(snap.online)
        assertEquals("", snap.host)
    }

    @Test
    fun sessao_inexistente_fica_offline() {
        val snap = RemoteProtocol.parseSession("""{"ok":false,"erro":"sessao inexistente"}""")
        assertFalse(snap.online)
    }

    @Test
    fun le_anfitriao_e_faixa_tocando() {
        val body = """
            {"ok":true,"code":"ABCDE","host":"dev-42","playing":true,
             "state":{"playing":true,"positionMs":65000,"volume":70,
                      "current":{"id":7,"title":"Aquarela","artist":"Toquinho",
                                 "album":"Aquarela","durationMs":180000}}}
        """.trimIndent()
        val snap = RemoteProtocol.parseSession(body)
        assertTrue(snap.online)
        assertEquals("dev-42", snap.host)
        assertEquals("ABCDE", snap.code)
        assertTrue(snap.playing)
        assertTrue(snap.hasTrack)
        assertEquals("Aquarela", snap.title)
        assertEquals("Toquinho", snap.artist)
        assertEquals(65000L, snap.positionMs)
        assertEquals(180000L, snap.durationMs)
        assertEquals(70, snap.volume)
    }

    @Test
    fun pausado_sem_faixa_nao_marca_playing() {
        val body = """{"ok":true,"code":"ZZZZZ","host":"dev-1","playing":false,
                       "state":{"playing":false,"current":null,"positionMs":0}}"""
        val snap = RemoteProtocol.parseSession(body)
        assertTrue(snap.online)
        assertFalse(snap.playing)
        assertFalse(snap.hasTrack)
        assertEquals(-1, snap.volume)
    }

    @Test
    fun monta_cmd_sem_valor() {
        assertEquals("""{"cmd":"toggle"}""", RemoteProtocol.cmdJson("toggle"))
    }

    @Test
    fun monta_cmd_com_valor_numerico() {
        assertEquals("""{"cmd":"seek","value":5000}""", RemoteProtocol.cmdJson("seek", 5000L))
        assertEquals("""{"cmd":"volume","value":80}""", RemoteProtocol.cmdJson("volume", 80))
    }
}
