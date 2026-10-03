package com.pulsa.player.dj

/**
 * A politica de "quando reabrir o microfone", fora do Android e fora do listener.
 *
 * Existe por causa de um bug que passou duas vezes: a espera ficava embutida em
 * `onResults`/`onError`, entao nenhuma suite testava a regra — e a regra estava errada
 * (o laço era infinito; a 5.9.3 so trocou 3s fixos por um backoff exponencial que
 * continuava sem fim). Aqui a regra e uma funcao pura, e o `MicCycleTest` cobre os dois
 * modos.
 */
class MicCycle(
    private val mode: MicMode,
    private val backoff: MicBackoff
) {
    /**
     * Quanto esperar antes de reabrir o microfone, ou `null` para nunca mais abrir.
     *
     * @param heard veio algum texto reconhecido nesta janela.
     */
    fun waitBeforeReopen(heard: Boolean): Long? = when (mode) {
        // Quem apertou o botao esta falando: se ouviu algo, reabre na hora para nao perder
        // o resto da frase. Se nao ouviu nada, a espera cresce (2s, 4s, 8s, 15s) — aqui o
        // laço é para ouvir a conversa, nao para procurar uma palavra.
        MicMode.CONTINUOUS -> if (heard) 0L else backoff.next()

        // Escuta por pedido (maos-livres e Virgin): uma janela, acabou, fecha. Nao reabre
        // por conta propria em nenhum caso — se reabrisse, era o "pisca sem parar" que o
        // usuario reclamou tres vezes.
        MicMode.ONE_SHOT -> null
    }

    /** Alguem realmente falou: a proxima espera volta ao comeco. */
    fun reset() = backoff.reset()
}