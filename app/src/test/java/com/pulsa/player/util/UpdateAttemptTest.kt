package com.pulsa.player.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressao do aviso de update que nunca mais aparecia: a marca de tentativa
 * era gravada logo apos o download, antes da instalacao, e o app achava que ja
 * tinha oferecido aquela versao para sempre.
 */
class UpdateAttemptTest {

    private val agora = 1_700_000_000_000L
    private val cooldown = 12L * 60L * 60L * 1000L

    private fun suppress(
        latestName: String = "5.9.3",
        attemptedName: String? = "5.9.3",
        attemptedCode: Long = 124L,
        attemptedAt: Long = agora - 60_000L,
        currentCode: Long = 123L
    ) = UpdateChecker.suppressAttempt(
        latestName = latestName,
        attemptedName = attemptedName,
        attemptedCode = attemptedCode,
        attemptedAt = attemptedAt,
        currentCode = currentCode,
        nowMs = agora
    )

    @Test
    fun tentativa_recem_e_aba_fada() {
        assertTrue(suppress())
    }

    @Test
    fun Instalacao_que_falhou_volta_a_oferecer() {
        // Baixou, mas a instalacao nao saiu: o codigo do app continua o antigo
        // e a janela ja passou -> o Pulsa oferece de novo.
        assertFalse(suppress(attemptedAt = agora - cooldown - 1L))
    }

    @Test
    fun instalou_entao_nao_repete_mesmo_depois_da_janela() {
        assertTrue(suppress(currentCode = 124L, attemptedAt = agora - cooldown - 1L))
    }

    @Test
    fun outra_versao_nunca_e_aba_fada() {
        assertFalse(suppress(latestName = "5.9.4", attemptedName = "5.9.3"))
    }

    @Test
    fun marca_antiga_sem_horario_nao_prende_o_update() {
        // Versoes antigas do app gravavam so o nome, sem carimbo de tempo:
        // nao da pra saber quando tentou, entao tem que re-oferecer.
        assertFalse(suppress(attemptedAt = 0L))
    }

    @Test
    fun sem_marca_nada_e_aba_fado() {
        assertFalse(suppress(attemptedName = null, attemptedCode = 0L, attemptedAt = 0L))
    }

    @Test
    fun marca_sem_codigo_usa_somente_a_janela() {
        assertTrue(suppress(attemptedCode = 0L))
        assertFalse(suppress(attemptedCode = 0L, attemptedAt = agora - cooldown - 1L))
    }

    @Test
    fun o_limite_da_janela_e_exato() {
        assertTrue(suppress(attemptedAt = agora - (cooldown - 1L)))
        assertFalse(suppress(attemptedAt = agora - cooldown))
    }
}
