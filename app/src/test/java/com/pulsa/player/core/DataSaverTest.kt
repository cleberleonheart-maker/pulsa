package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 291 — a decisão de economia de dados tem que ser conservadora: só rebaixa o vídeo com
 * o ajuste ligado **e** a rede medida. Wi-Fi nunca paga a queda de qualidade, e quem desligou
 * o ajuste quer a melhor imagem mesmo no 4G.
 */
class DataSaverTest {

    @Test
    fun soLimitaComAjusteLigadoERedeMedida() {
        assertTrue(DataSaver.shouldLimit(enabled = true, metered = true))
        assertFalse(DataSaver.shouldLimit(enabled = true, metered = false))
        assertFalse(DataSaver.shouldLimit(enabled = false, metered = true))
        assertFalse(DataSaver.shouldLimit(enabled = false, metered = false))
    }

    @Test
    fun redeMedidaComAjusteDaOTetoDeResolucao() {
        assertEquals(
            DataSaver.METERED_MAX_WIDTH to DataSaver.METERED_MAX_HEIGHT,
            DataSaver.maxSize(enabled = true, metered = true)
        )
        assertEquals(DataSaver.METERED_MAX_BITRATE, DataSaver.maxBitrate(enabled = true, metered = true))
    }

    @Test
    fun wiFiSolturaOPlayout() {
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, DataSaver.maxSize(enabled = true, metered = false))
        assertEquals(Int.MAX_VALUE, DataSaver.maxBitrate(enabled = true, metered = false))
    }

    @Test
    fun ajusteDesligadoSolturaOPlayoutMesmoNoQuatroG() {
        assertEquals(Int.MAX_VALUE to Int.MAX_VALUE, DataSaver.maxSize(enabled = false, metered = true))
        assertEquals(Int.MAX_VALUE, DataSaver.maxBitrate(enabled = false, metered = true))
    }
}
