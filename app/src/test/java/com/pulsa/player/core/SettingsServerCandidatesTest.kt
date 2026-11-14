package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A regra dos candidatos de servidor: **em claro só no modo LAN**.
 *
 * Antes, os candidatos `http://192.168.100.7:8081` e `http://127.0.0.1:8081` eram
 * acrescentados em **todos** os modos. Com o servidor configurado (ou o túnel) fora do ar,
 * o app caía no IP da rede local e mandava o token, a biblioteca e o e-mail da pessoa em
 * claro — sem HTTPS, sem ninguém ver. O endereço configurado é escolha de quem configurou;
 * o padrão não é, e por isso o app não desce de segurança sozinho.
 *
 * O teste trava a regra porque ela é de segurança: segurança sem teste é intenção.
 */
class SettingsServerCandidatesTest {

    @Test
    fun modoLanUsaOsCandidatosDaRede() {
        val cands = Settings.serverCandidatesFor("lan", "", null)
        assertTrue(cands.contains(Settings.LAN_SERVER_URL))
        assertTrue(cands.contains(Settings.LOCAL_SERVER_URL))
        assertTrue(cands.contains(Settings.DEFAULT_SERVER_URL))
    }

    @Test
    fun foraDoLanNaoEntraNenhumCandidatoEmClaro() {
        for (modo in listOf("online", "custom")) {
            val cands = Settings.serverCandidatesFor(modo, "https://meu.exemplo", "https://tunel.exemplo")
            val claros = cands.filter { it.startsWith("http://") }
            assertTrue("modo $modo nao deveria ter candidato em claro: $cands", claros.isEmpty())
        }
    }

    @Test
    fun modoOnlineComecaPeloTunel() {
        val cands = Settings.serverCandidatesFor("online", "auto", "https://tunel.exemplo")
        assertEquals("https://tunel.exemplo", cands.first())
        assertEquals(Settings.DEFAULT_SERVER_URL, cands.last())
    }

    @Test
    fun modoOnlineSemTunelCaiNoServidorPublicoEmHttps() {
        val cands = Settings.serverCandidatesFor("online", "auto", null)
        assertEquals(listOf(Settings.DEFAULT_SERVER_URL), cands)
    }

    @Test
    fun modoCustomRespeitaOEnderecoEscolhido() {
        val cands = Settings.serverCandidatesFor("custom", "https://meu.exemplo", null)
        assertEquals("https://meu.exemplo", cands.first())
        assertEquals(Settings.DEFAULT_SERVER_URL, cands.last())
    }

    @Test
    fun modoCustomComEnderecoEmClaroNaoGeraOutrosEmClaro() {
        // O endereço em claro escolhido pela pessoa e respeitado — mas o app nao acrescenta
        // nenhum outro candidato em claro por baixo dela.
        val cands = Settings.serverCandidatesFor("custom", "http://192.168.0.9:8081", null)
        assertEquals("http://192.168.0.9:8081", cands.first())
        assertEquals(1, cands.count { it.startsWith("http://") })
        assertEquals(Settings.DEFAULT_SERVER_URL, cands.last())
    }

    @Test
    fun nenhumCandidatoRepetido() {
        val cands = Settings.serverCandidatesFor("lan", "", null)
        assertEquals(cands.size, cands.distinct().size)
    }

    @Test
    fun modoDesconhecidoNaoViraCleartext() {
        // Se um dia aparecer um modo novo, ele nao pode herdar os candidatos em claro:
        // so o "lan" declarado usa o servidor em claro da rede.
        val cands = Settings.serverCandidatesFor("modo-novo", "", null)
        assertTrue(cands.none { it.startsWith("http://") })
        assertEquals(Settings.DEFAULT_SERVER_URL, cands.first())
    }
}
