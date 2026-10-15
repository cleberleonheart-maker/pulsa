package com.pulsa.player.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * O vai-e-vem do código de sessão: o que o host desenha no QR e o que o convidado aceita
 * depois de escanear. É a parte do "ouvir juntos" que dá para provar sem câmera, e é onde
 * o erro apareceria como "código inválido" lendo um QR que o próprio app gerou.
 */
class MirrorQrTest {

    @Test
    fun encodeViraUriComCodigoEmMaiusculas() {
        assertEquals("pulsa://sessao/ABCDE", MirrorQr.encode("abcde"))
        assertEquals("pulsa://sessao/ABCDE", MirrorQr.encode("  AbCdE "))
    }

    @Test
    fun codeFromAceitaAUriDoProprioApp() {
        assertEquals("ABCDE", MirrorQr.codeFrom("pulsa://sessao/ABCDE"))
        assertEquals("ABCDE", MirrorQr.codeFrom("PULSA://SESSAO/abcde"))
    }

    @Test
    fun codeFromAceitaCodigoCru() {
        assertEquals("ABCDE", MirrorQr.codeFrom("  abcde "))
        assertEquals("ABCDE", MirrorQr.codeFrom("ABCDE"))
    }

    @Test
    fun codeFromIgnoraQrDeOutraCoisa() {
        assertNull(MirrorQr.codeFrom("https://example.com/qr"))
        assertNull(MirrorQr.codeFrom("wifi:T:WPA;S:minharede;;"))
        assertNull(MirrorQr.codeFrom("AB"))
        assertNull(MirrorQr.codeFrom(""))
        assertNull(MirrorQr.codeFrom(null))
    }
}
