package com.pulsa.player.media.tv

/**
 * A1 — interpretar o cabeçalho `Range` de um pedido HTTP, sem rede.
 *
 * **Por que funções puras aqui.** O [DlnaServer] precisa servir mídia com `Range`/206 porque
 * a Roku Media Player (e qualquer player DLNA decente) usa o salto de tempo pedindo só o
 * pedaço novo — sem 206 o seek da TV vira reload do arquivo inteiro, e em vídeo grande isso
 * é o mesmo que travar. Erro de parse aqui é seek quebrado na TV, então os casos reais ficam
 * cobertos por teste JVM, sem aparelho.
 *
 * Formas aceitas pela RFC 7233 que aparecem no mundo real:
 *   - `bytes=0-499`   → pede os primeiros 500 bytes;
 *   - `bytes=500-`    → do 500 até o fim;
 *   - `bytes=-500`    → os últimos 500 bytes (suffixo);
 *   - `bytes=0-99999` → fim maior que o arquivo: corta no fim real (o RFC manda servir o
 *     que existe e não erro).
 *
 * `Range` com múltiplos pedaços (`bytes=0-1,5-6`) não existe em player de TV e não é
 * aceito aqui: devolver o primeiro pedaço como se fosse o único engana o player com um
 * `Content-Range` que não casa com o pedido. Nesse caso o retorno é `null` (resposta
 * completa 200), que é o fallback seguro da RFC.
 */
object HttpRange {

    /** Pedaco pedido, com fim **inclusivo** (convenção do HTTP, ao contrário de `until`). */
    data class Spec(val start: Long, val endInclusive: Long) {
        val length: Long get() = endInclusive - start + 1
    }

    /**
     * O pedaço pedido, ou `null` quando não há `Range` (ou o que veio não é aproveitável)
     * e a resposta deve ser o arquivo inteiro com 200.
     *
     * `total` é o tamanho real: sem ele o sufixo (`bytes=-500`) e o corte do fim não têm
     * como ser calculados.
     */
    fun parse(header: String?, total: Long): Spec? {
        if (header.isNullOrBlank() || total <= 0L) return null
        val value = header.trim()
        if (!value.startsWith("bytes=", ignoreCase = true)) return null
        val spec = value.removePrefix("bytes=").removePrefix("BYTES=").trim()
        // Múltiplos intervalos: RFC manda 200 com o arquivo inteiro (fallback seguro).
        if (spec.contains(',')) return null
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val from = spec.substring(0, dash).trim()
        val to = spec.substring(dash + 1).trim()
        return try {
            when {
                from.isEmpty() -> {
                    // Sufixo: últimos N bytes. N >= tamanho devolve o arquivo todo.
                    val suffix = to.toLong()
                    if (suffix <= 0L) null else Spec((total - suffix).coerceAtLeast(0L), total - 1L)
                }
                to.isEmpty() -> {
                    val start = from.toLong()
                    if (start >= total) null else Spec(start, total - 1L)
                }
                else -> {
                    var start = from.toLong()
                    var end = to.toLong()
                    if (start > end || start >= total) return null
                    // Fim além do arquivo: corta no fim real, o RFC manda servir o que existe.
                    if (end >= total) end = total - 1L
                    Spec(start, end)
                }
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
