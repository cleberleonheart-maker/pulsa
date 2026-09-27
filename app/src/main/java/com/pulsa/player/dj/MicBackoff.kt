package com.pulsa.player.dj

/**
 * Espera crescente para reabrir o microfone das maos-livres.
 *
 * O ciclo de ~3s que o usuario viu na 5.9.1 era o reconhecedor devolvendo
 * resultado vazio (musica tocando, ninguem falando) e voltando a abrir o
 * microfone na hora. Cada [next] dobra a espera ate [maxMs]; [reset] volta ao
 * comeco quando alguem realmente falou.
 */
class MicBackoff(
    private val firstMs: Long,
    private val maxMs: Long
) {
    private var currentMs: Long = firstMs

    /** Quanto esperar antes de reabrir o microfone agora, ja preparando a proxima. */
    fun next(): Long {
        val wait = currentMs
        currentMs = (currentMs * 2).coerceAtMost(maxMs)
        return wait
    }

    fun reset() {
        currentMs = firstMs
    }
}
