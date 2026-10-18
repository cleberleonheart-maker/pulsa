package com.pulsa.player.dj

import android.content.Context
import com.pulsa.player.audio.SongBpm
import com.pulsa.player.model.Song

/**
 * BPM nas cenas — "toca pra malhar" escolhendo por andamento de verdade.
 *
 * A cena já filtra gênero e intensidade; isto acrescenta a terceira dimensão que faltava
 * (o item 129/169 do IDEIAS, que pedia tempo por minuto). Tudo é progressivo e opcional
 * no melhor sentido: sem dado nenhum a cena funciona como sempre funcionou, e cada pedido
 * deixa um lote analisado ([analisarLote]) para a próxima fila ser por andamento.
 *
 * Puro: [janela], [aplicar] e [paraAnalise] decidem sem tocar em arquivo nem banco — os
 * testes em `SceneBpmTest` cobrem a janela de cada cena e o fallback, que é a parte que
 * não pode errar (filtrar com 2 faixas repetiria as mesmas 2 para sempre).
 */
object SceneBpm {

    /**
     * Mínimo de faixas do pool já conhecidas E dentro da janela para o filtro valer.
     * Abaixo disso devolve o pool inteiro — o corte deixaria a fila capenga e sempre
     * repetiria as mesmas faixas até a cobertura crescer.
     */
    const val MIN_FILTRADAS = 6

    /** Quantas faixas ainda sem BPM cada pedido de cena analisa em background. */
    const val LOTE = 20

    /** Janela de andamento de cada cena, na mesma escala do estimador (70–185 BPM). */
    fun janela(cena: String): ClosedFloatingPointRange<Float>? = when (cena) {
        DjCommander.SCENE_MALHAR -> 120f..185f
        DjCommander.SCENE_DIRIGIR -> 100f..160f
        DjCommander.SCENE_VIAJAR -> 85f..130f
        DjCommander.SCENE_ESTUDAR -> 70f..105f
        else -> null
    }

    /**
     * Pool filtrado pela janela da cena; devolve o mesmo pool quando menos de
     * [MIN_FILTRADAS] faixas **conhecidas** caem nela (o carimbo 0 = inconclusivo também
     * fica de fora, senão "analisado e ruim" seria pior que "nunca analisado").
     */
    fun aplicar(
        pool: List<Song>,
        bpms: Map<Long, Float>,
        janela: ClosedFloatingPointRange<Float>,
    ): List<Song> {
        val naJanela = pool.filter { s ->
            val b = bpms[s.id] ?: return@filter false
            b > 0f && b in janela
        }
        return if (naJanela.size >= MIN_FILTRADAS) naJanela else pool
    }

    /**
     * Próximo lote a analisar: faixas do pool ainda sem registro, de trás pra frente
     * (ordem estável — a cobertura avança sistematicamente em vez de girar na mesma).
     *
     * Só arquivo local de verdade dá o que decodificar: rádio/stream/vídeo não têm caminho
     * e faixa de menos de 15 s não reúne material para a autocorrelação.
     */
    fun paraAnalise(pool: List<Song>, conhecidos: Map<Long, Float>, limite: Int): List<Song> =
        pool.filter { s ->
            s.id !in conhecidos &&
                !s.isRadio && !s.isVideo && !s.isStream &&
                s.path.startsWith("/") &&
                s.durationMs >= 15_000L
        }.take(limite)

    /**
     * Analisa o lote e grava no `dj_learn.db` (0 = inconclusivo, guardado para a cena não
     * decodificar a mesma faixa ruim em todo pedido). Roda em background, um pedido por
     * vez, chamado depois da fila já estar tocando — a música nunca espera por isso.
     */
    fun analisarLote(context: Context, lote: List<Song>) {
        for (s in lote) {
            val bpm = runCatching { SongBpm.analisarArquivo(s.path) }.getOrDefault(0f)
            DjLearn.putBpm(context, s.id, bpm)
        }
    }
}
