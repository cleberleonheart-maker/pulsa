package com.pulsa.player.media

import com.pulsa.player.media.OfflineCleanup.Candidate
import com.pulsa.player.media.OfflineCleanup.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A decisão da limpeza do offline. O custo de errar aqui é apagar um baixado que a pessoa
 * ainda queria, então os testes cobram mais o **que fica** do que o que sai: desligado não
 * apaga nada, dado sem data não apaga, e o que está tocando nunca entra.
 */
class OfflineCleanupTest {

    private val now = 1_000L * 24 * 60 * 60 * 1000

    private fun days(n: Long) = n * 24L * 60 * 60 * 1000

    private fun video(id: String, ageDays: Long, bytes: Long = 100L, lastUsed: Long? = null) = Candidate(
        id = id,
        title = "v-$id",
        kind = Kind.VIDEO,
        bytes = bytes,
        lastUsedMs = lastUsed ?: (now - days(ageDays))
    )

    private fun episode(id: String, ageDays: Long, bytes: Long = 50L) = Candidate(
        id = id,
        title = "e-$id",
        kind = Kind.EPISODE,
        bytes = bytes,
        lastUsedMs = now - days(ageDays)
    )

    @Test
    fun desligadoNaoApagaNada() {
        val plan = OfflineCleanup.plan(listOf(video("a", 999)), now, days = 0)
        assertEquals(0, plan.count)
    }

    @Test
    fun apagaSoOQuePassouDoCorte() {
        val plan = OfflineCleanup.plan(
            listOf(video("velho", 40), video("novo", 3)),
            now, days = 30
        )
        assertEquals(listOf("velho"), plan.videos.map { it.id })
        assertTrue(plan.episodes.isEmpty())
    }

    @Test
    fun separaVideoDeEpisodioESoma() {
        val plan = OfflineCleanup.plan(
            listOf(video("v", 90, bytes = 1000), episode("7", 90, bytes = 500)),
            now, days = 60
        )
        assertEquals(1, plan.videos.size)
        assertEquals(1, plan.episodes.size)
        assertEquals(2, plan.count)
        assertEquals(1500L, plan.bytes)
    }

    @Test
    fun protegidoNuncaEntra() {
        val plan = OfflineCleanup.plan(
            listOf(video("tocando", 90), video("outro", 90)),
            now, days = 60, protected = setOf("tocando")
        )
        assertEquals(listOf("outro"), plan.videos.map { it.id })
    }

    @Test
    fun semDataNaoApagaNaDuvida() {
        // `lastUsedMs = 0` é "não sei quando foi usado". O viés é preservar.
        val plan = OfflineCleanup.plan(
            listOf(video("sem-data", 0, lastUsed = 0L)),
            now, days = 7
        )
        assertEquals(0, plan.count)
    }

    @Test
    fun arquivoSumidoNaoConta() {
        // `bytes = 0` é registro órfão: apagar não libera nada.
        val plan = OfflineCleanup.plan(listOf(video("orfa", 90, bytes = 0L)), now, days = 30)
        assertEquals(0, plan.count)
    }

    @Test
    fun exatamenteNoCorteFica() {
        val plan = OfflineCleanup.plan(
            listOf(video("limite", 30, lastUsed = now - days(30))),
            now, days = 30
        )
        assertEquals(0, plan.count)
    }
}
