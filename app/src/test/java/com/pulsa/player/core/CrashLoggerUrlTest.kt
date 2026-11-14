package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * O que pode entrar no log: o caminho da URL, nunca a query.
 *
 * A query é onde mora o segredo — enclosure de podcast assinado (`?token=…&expira=…`),
 * link temporário de download, token de acesso. E o log do app é espelhado em
 * `Download/Pulsa/pulsa-erros.log`, que qualquer app com permissão de armazenamento lê.
 *
 * O caminho continua no log porque é ele que diz **qual** servidor falhou; sem ele o log
 * não serve para nada.
 */
class CrashLoggerUrlTest {

    @Test
    fun tiraAQueryString() {
        assertEquals(
            "https://cdn.exemplo.com/podcast/ep1.mp3",
            CrashLogger.urlSemQuery("https://cdn.exemplo.com/podcast/ep1.mp3?token=segredo&expira=123")
        )
    }

    @Test
    fun urlSemQueryFicaComoEsta() {
        assertEquals(
            "https://pulsa.exemplo.com/state",
            CrashLogger.urlSemQuery("https://pulsa.exemplo.com/state")
        )
    }

    @Test
    fun queryVaziaNaoMudaNada() {
        assertEquals("https://exemplo.com/a", CrashLogger.urlSemQuery("https://exemplo.com/a?"))
    }

    @Test
    fun oTokenNaoApareceNoLog() {
        val segredo = "https://cdn.exemplo.com/ep.mp3?token=SEGREDO-DO-ENCLOSURE&expira=99"
        val seguro = CrashLogger.urlSemQuery(segredo)
        assertFalse(seguro.contains("SEGREDO-DO-ENCLOSURE"))
        assertFalse(seguro.contains("expira"))
        assertFalse(seguro.contains("?"))
    }

    @Test
    fun fragmentoTambemSomeDaQuery() {
        // A query vem antes do fragmento: cortar no "?" já leva o "#" junto.
        assertEquals(
            "https://exemplo.com/a",
            CrashLogger.urlSemQuery("https://exemplo.com/a?x=1#parte")
        )
    }
}
