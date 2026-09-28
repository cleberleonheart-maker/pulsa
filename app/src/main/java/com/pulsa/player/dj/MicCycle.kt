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
     * Quanto esperar antes de reabrir o microfone.
     *
     * @param heard veio algum texto reconhecido nesta janela.
     */
    fun waitBeforeReopen(heard: Boolean): Long = when (mode) {
        // Quem apertou o botao esta falando: se ouviu algo, reabre na hora para nao perder
        // o resto da frase. Se nao ouviu nada, a espera cresce (2s, 4s, 8s, 15s) — aqui o
        // laço e para ouvir a conversa, e nao para procurar uma palavra.
        MicMode.CONTINUOUS -> if (heard) 0L else backoff.next()

        // Espera da palavra: tanto tendo ouvido algo (a palavra e o comando) quanto nao, a
        // janela fecha e o microfone descansa. Nao ha backoff aqui — o objetivo e nao abrir.
        MicMode.WORD_WATCH -> MicMode.DUTY_IDLE_MS
    }

    /** Alguem realmente falou: a proxima espera volta ao comeco. */
    fun reset() = backoff.reset()
}
