package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A regra do token da nuvem: o token próprio manda; em branco (ou só espaços), vale o
 * token normal do servidor.
 *
 * Isso importa porque o servidor público recusa `/cloud` com o token normal (achado de
 * 04/11/2026): o campo novo nas Configurações é a forma de resolver sem esperar o
 * servidor mudar, e "campo vazio = token normal" é o que mantém o comportamento antigo
 * para quem não mexer em nada.
 */
class SettingsCloudTokenTest {

    @Test
    fun tokenProprioManda() {
        assertEquals("segredo-da-nuvem", Settings.resolveCloudToken("segredo-da-nuvem", "pulsa-local-2026"))
    }

    @Test
    fun vazioUsaTokenNormal() {
        assertEquals("pulsa-local-2026", Settings.resolveCloudToken("", "pulsa-local-2026"))
    }

    @Test
    fun apenasEspacosUsaTokenNormal() {
        assertEquals("pulsa-local-2026", Settings.resolveCloudToken("   ", "pulsa-local-2026"))
    }

    @Test
    fun tokenProprioComEspacosNasExtremidades() {
        assertEquals("segredo", Settings.resolveCloudToken("  segredo  ", "pulsa-local-2026"))
    }
}
