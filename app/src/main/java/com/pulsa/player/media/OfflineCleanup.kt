package com.pulsa.player.media

/**
 * F5 · Limpeza automática do offline — **só a decisão**, sem Android nenhum.
 *
 * A parte que erra fácil não é apagar arquivo: é decidir *o que* apagar. Por isso a escolha
 * mora aqui, pura e testável, e quem toca em disco (o Worker) só obedece ao [Plan]. Um
 * `plan()` que apagasse por engano baixados novos derrubaria a confiança na função inteira,
 * então cada regra de guarda tem o seu teste em `OfflineCleanupTest`.
 *
 * **Idade = último uso**, não data de publicação nem de download sozinho: [Candidate.lastUsedMs]
 * já chega somando tudo que o app sabe (download, mtime do arquivo, histórico de reprodução),
 * e aqui só se compara com o corte.
 */
object OfflineCleanup {

    enum class Kind { VIDEO, EPISODE }

    /**
     * Uma coisa baixada que pode ser apagada.
     *
     * [id] é a chave que o chamador usa para apagar (id do download, ou id do episódio em
     * texto); [path] vai junto só para o apagador não ter que reencontrar o arquivo.
     */
    data class Candidate(
        val id: String,
        val title: String,
        val kind: Kind,
        val bytes: Long,
        val lastUsedMs: Long,
        val path: String = ""
    )

    data class Plan(val videos: List<Candidate>, val episodes: List<Candidate>) {
        val count: Int get() = videos.size + episodes.size
        val bytes: Long get() = videos.sumOf { it.bytes } + episodes.sumOf { it.bytes }
    }

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /**
     * O que apagar: tudo que passou de [days] sem uso.
     *
     * As guardas, em ordem — qualquer uma tira o candidato da lista:
     * - `days <= 0` desliga a função: sem isto, "desligado" (o padrão) apagaria tudo;
     * - `bytes <= 0` não é baixado de verdade (arquivo sumiu) — apagar não libera nada e só
     *   perderia o registro;
     * - `id` protegido: é o que está tocando agora, e derrubar o arquivo em uso travaria a
     *   reprodução;
     * - `lastUsedMs <= 0`: sem nenhuma data não dá para provar que é antigo, então **não mexe**
     *   — o viés aqui é sempre preservar, nunca apagar na dúvida.
     */
    fun plan(
        candidates: List<Candidate>,
        nowMs: Long,
        days: Int,
        protected: Set<String> = emptySet()
    ): Plan {
        if (days <= 0) return Plan(emptyList(), emptyList())
        val cutoff = nowMs - days * DAY_MS
        val doomed = candidates.filter { c ->
            c.bytes > 0L && c.id !in protected && c.lastUsedMs > 0L && c.lastUsedMs < cutoff
        }
        return Plan(
            videos = doomed.filter { it.kind == Kind.VIDEO },
            episodes = doomed.filter { it.kind == Kind.EPISODE }
        )
    }
}
