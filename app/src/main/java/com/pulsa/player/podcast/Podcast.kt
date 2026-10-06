package com.pulsa.player.podcast

/**
 * O modelo de podcast: um feed assinado e os episódios dele.
 *
 * Foi a lacuna mais funda da central de mídia — até aqui o app tinha áudio e vídeo na mesma
 * fila, mas podcast era uma pasta do MediaStore que o scanner varria como se fosse álbum. Não
 * havia feed, assinatura, episódio nem "marcar ouvido", que é justamente o que distingue um
 * player de podcast de um player de música.
 *
 * A identidade do episódio é o **`guid` do feed**, e não a URL do áudio. URL mente: CDNs
 * mandam o mesmo episódio com assinatura e expiração diferentes a cada requisição, e alguns
 * feeds trocam de host na virada do ano. Se a chave fosse a URL, cada atualização do feed
 * criaria os mesmos episódios de novo — e a lista cresceria sozinha, com duplicatas que o
 * usuário não criou. Feed nenhum é perfeito com `guid`: os mais simples não têm. Por isso o
 * `guid` cai para a URL quando não existe, mas a URL é o **último** recurso.
 */
data class Podcast(
    /** Chave estável do feed: o id do Room. `0` enquanto o feed não foi salvo. */
    val id: Long = 0L,
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String = "",
    val episodes: List<Episode> = emptyList()
)

data class Episode(
    val id: Long = 0L,
    val podcastId: Long = 0L,
    /** `guid` do item, ou a URL quando o feed não tem guid. */
    val guid: String,
    val title: String,
    val description: String = "",
    val audioUrl: String,
    /** Duração em segundos, como o feed publica. `0` quando o feed omite. */
    val durationSec: Long = 0L,
    /** Data de publicação em epoch **milissegundos**; `0` quando o feed omite. */
    val publishedAt: Long = 0L,
    val artworkUrl: String = "",
    /**
     * `true` quando o `enclosure` tem `type` de vídeo — podcast em vídeo.
     *
     * Existe porque o `FeedParser` aceita o enclosure pelo `url` sem olhar o `type`, e um feed
     * que mistura áudio e vídeo (ou só vídeo, que é o formato comum hoje) entraria na fila
     * como se fosse faixa de áudio. O sintoma é o player tocar um vídeo sem imagem nenhuma.
     */
    val isVideo: Boolean = false,
    /**
     * Endereço da página do episódio quando não há enclosure, mas o item aponta para vídeo —
     * é como podcast em vídeo costuma publicar, e o que a busca do YouTube vai resolver.
     */
    val videoPageUrl: String = "",
    /** Onde o episódio baixado está, se estiver. Vazio = só streaming. */
    val filePath: String = "",
    /** Ouvido até o fim — o "marcar ouvido", que é o que separa podcast de música. */
    val played: Boolean = false,
    /** Atraso em ms, para retomar o episódio no meio. `0` quando não há. */
    val positionMs: Long = 0L
) {
    val downloaded: Boolean get() = filePath.isNotBlank()

    /**
     * O caminho que o ExoPlayer deve abrir: o arquivo baixado, ou a URL do feed.
     *
     * O arquivo tem precedência **por ser offline** — é o que faz o episódio tocar no túnel.
     * Para podcast em vídeo sem arquivo, não há nada local: a [videoPageUrl] é o que sobrou,
     * e quem tenta abrir descobre pelo erro do player em vez de ganhar uma tela preta.
     */
    val playableUrl: String get() = filePath.ifBlank { if (isVideo) videoPageUrl else audioUrl }

    /**
     * `true` quando o usuário chegou ao fim — conta como ouvido mesmo sem marcar à mão.
     *
     * Base de 90%: quase todo podcast tem vinheta e dinâmica variável, e o tempo reportado
     * pela plataforma raramente bate com o arquivo. Usar o fim exato (`>= 99%`) faria a
     * maioria dos episódios concluídos continuar aparecendo como não ouvido para sempre;
     * usar menos que isso marcaria como ouvido o que a pessoa pulou nos primeiros segundos.
     */
    val finished: Boolean get() =
        played || (durationSec > 0 && positionMs > 0 && positionMs >= durationSec * 900L)
}
