package com.pulsa.player.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decisão do teto offline. O caso que importa é o podcast: antes ele não entrava na conta,
 * então o usuário podia estourar o limite escolhido sem o app perceber. O teste é puro porque
 * a soma real (Room + MediaStore) é do aparelho; aqui só a regra do "cabe ou não".
 */
class OfflineSpaceTest {

    private val mb = 1024L * 1024L

    @Test
    fun semLimiteNuncaEstoura() {
        assertFalse(OfflineSpace.exceeds(usedBytes = 10_000 * mb, limitBytes = 0L, extra = 0L))
    }

    @Test
    fun podcastNoUsoFazOVideoEstourarAntes() {
        // 900 MB de podcast + 200 MB de vídeo num teto de 1 GB: o vídeo já não cabe.
        val used = 900L * mb
        assertTrue(OfflineSpace.exceeds(usedBytes = used, limitBytes = 1024L * mb, extra = 200L * mb))
    }

    @Test
    fun extraDoDownloadEntraNaConta() {
        assertTrue(OfflineSpace.exceeds(usedBytes = 900L * mb, limitBytes = 1024L * mb, extra = 200L * mb))
        assertFalse(OfflineSpace.exceeds(usedBytes = 900L * mb, limitBytes = 1024L * mb, extra = 100L * mb))
    }

    @Test
    fun exatamenteNoTetoAindaCabe() {
        assertFalse(OfflineSpace.exceeds(usedBytes = 1024L * mb, limitBytes = 1024L * mb, extra = 0L))
    }
}
