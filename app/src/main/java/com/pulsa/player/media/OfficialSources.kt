package com.pulsa.player.media

import org.json.JSONObject
import java.net.URLEncoder

/**
 * As fontes oficiais que o app sabe resolver, e o trabalho puro de cada uma.
 *
 * **O que é "oficial" aqui.** São APIs públicas do próprio serviço, sem chave e sem raspar
 * HTML: a iTunes Search/Lookup da Apple, a API do Deezer e a API do Internet Archive. Nenhuma
 * delas precisa de conta, e nenhuma entrega DRM — o que sai daqui é arquivo que o app pode
 * tocar e baixar de verdade.
 *
 * **O que continua fora, e por quê.** O Spotify tem API, mas ela não entrega áudio: o
 * catálogo é DRM e a faixa só sai pelo cliente oficial. O YouTube não publica URL de mídia —
 * a API oficial dá metadados, e o stream real exige o cliente do site. O SoundCloud, o Tidal e
 * o Vimeo exigem credencial de cliente para a busca. O Podcast Index exige chave (e é o
 * caminho opcional, separado, em `PodcastNet`).
 *
 * Este arquivo é **puro**: sem `Context`, sem `Looper`, sem rede. A rede fica no
 * [OfficialMediaResolver], e o que está aqui é o que dá para testar de verdade.
 */
object OfficialSources {

    /** De onde veio a resolução. */
    enum class Source { APPLE, DEEZER, ARCHIVE }

    /**
     * A fonte que sabe resolver esta URL, ou `null` se nenhuma sabe.
     *
     * O host é fixo por fonte: a URL da API é montada à mão, e um termo de busca com `../`
     * não pode escolher outro host.
     */
    fun sourceOf(url: String): Source? {
        val host = hostOf(url) ?: return null
        return when {
            host == "music.apple.com" || host == "itunes.apple.com" -> Source.APPLE
            host == "deezer.com" || host == "www.deezer.com" -> Source.DEEZER
            host == "archive.org" || host == "www.archive.org" -> Source.ARCHIVE
            else -> null
        }
    }

    /** O host da URL, em minúsculas, sem `www` inicial. */
    fun hostOf(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return null
        val afterScheme = trimmed.substringAfter("://")
        val host = afterScheme.substringBefore('/').substringBefore('?').lowercase()
        if (host.isBlank() || host.contains(' ')) return null
        return host.removePrefix("www.")
    }

    // ---- Apple ---------------------------------------------------------------------------

    /**
     * O id da Apple que esta URL pede.
     *
     * Duas formas: `?i=` é a **faixa** (é o link que o app do iTunes compartilha), e o número
     * no caminho é a **coleção** (álbum). O número do caminho nunca é o último segmento: o
     * link de álbum da Apple tem a posição da faixa no fim (`/album/nome/1440857781/17`), e
     * pegar o último dízimo pegaria o "17".
     */
    fun appleLookupId(url: String): String? {
        val query = url.substringAfter('?', "").substringBefore('#')
        for (part in query.split('&')) {
            val kv = part.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("i", ignoreCase = true)) {
                val id = kv[1].trim()
                if (id.isNotEmpty() && id.all { it.isDigit() }) return id
            }
        }
        val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
        for (segment in path.split('/')) {
            if (segment.length >= 6 && segment.all { it.isDigit() }) return segment
        }
        return null
    }

    /**
     * `true` quando o id é de uma coleção (álbum) e a busca precisa das faixas.
     *
     * Um link de álbum não tem faixa: pedir a música dele é pedir a **primeira** faixa do
     * álbum, que é o que a pessoa quer quando cola o link do álbum.
     */
    fun appleIsCollection(url: String): Boolean {
        val query = url.substringAfter('?', "").substringBefore('#')
        for (part in query.split('&')) {
            val kv = part.split('=', limit = 2)
            if (kv.size == 2 && kv[0].equals("i", ignoreCase = true)) return false
        }
        return true
    }

    /** A URL da busca da Apple. */
    fun appleLookupUrl(id: String, collection: Boolean): String =
        "https://itunes.apple.com/lookup?id=$id" + if (collection) "&entity=song" else ""

    /**
     * O primeiro preview da resposta da Apple, com o nome da faixa.
     *
     * A resposta de álbum traz o álbum na primeira linha e as faixas nas seguintes; a de
     * faixa traz a faixa. Nos dois casos, o que interessa é a **primeira faixa com preview**:
     * álbum sem preview é só um item de catálogo.
     */
    fun applePreview(json: String): Pair<String, String>? {
        val results = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return null
        for (i in 0 until results.length()) {
            val row = results.optJSONObject(i) ?: continue
            val preview = row.optString("previewUrl", "")
            if (!preview.startsWith("http")) continue
            val track = row.optString("trackName", "")
            val artist = row.optString("artistName", "")
            val name = buildString {
                if (artist.isNotBlank()) append(artist).append(" - ")
                if (track.isNotBlank()) append(track) else append("itunes")
            }
            return preview to name
        }
        return null
    }

    // ---- Deezer --------------------------------------------------------------------------

    /**
     * O id do Deezer: a **faixa** (`/track/123`) ou o **álbum** (`/album/123`).
     *
     * O id do álbum e o da faixa são a mesma coisa em base 10, mas a API é outra: `/track/`
     * devolve uma faixa, `/album/` devolve a lista. Por isso o tipo vem junto do id.
     */
    fun deezerId(url: String): Pair<String, Boolean>? {
        val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        for (i in segments.indices) {
            val kind = segments[i].lowercase()
            if (kind == "track" || kind == "album") {
                val id = segments.getOrNull(i + 1)?.trim().orEmpty()
                if (id.isNotEmpty() && id.all { it.isDigit() }) {
                    return id to (kind == "album")
                }
            }
        }
        return null
    }

    /** A URL da API do Deezer. */
    fun deezerLookupUrl(id: String, album: Boolean): String =
        "https://api.deezer.com/" + if (album) "album/" else "track/" + id

    /**
     * O preview do Deezer: a faixa itself, ou a primeira faixa do álbum.
     *
     * O preview do Deezer é um MP3 de 30 s — o mesmo limite do Apple's. Serve para ouvir e
     * para tocar no carro; não é a faixa completa.
     *
     * **A forma do álbum é outra.** A resposta de faixa traz `preview` na raiz; a de álbum
     * traz `tracks.data[]`, e o preview está na **primeira** faixa da lista. Pegar a raiz do
     * álbum dava sempre "sem preview" — e o álbum era justamente o link que colava.
     */
    fun deezerPreview(json: String): Pair<String, String>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val track = root.optJSONObject("track")
            ?: root.optJSONObject("tracks")?.optJSONArray("data")?.optJSONObject(0)
            ?: root
        val preview = track.optString("preview", "")
        if (!preview.startsWith("http")) return null
        val title = track.optString("title", "")
        val artist = runCatching { track.optJSONObject("artist")?.optString("name", "") }
            .getOrNull()
            .orEmpty()
        val name = buildString {
            if (artist.isNotBlank()) append(artist).append(" - ")
            if (title.isNotBlank()) append(title) else append("deezer")
        }
        return preview to name
    }

    // ---- Internet Archive ----------------------------------------------------------------

    /**
     * O identificador do item no Internet Archive.
     *
     * Só a página de detalhes vale: a de download (`/download/<id>/<arquivo>`) já é o arquivo
     * direto, e a de download não tem nada a resolver.
     */
    fun archiveIdentifier(url: String): String? {
        val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
        val segments = path.split('/').filter { it.isNotBlank() }
        val details = segments.indexOf("details")
        if (details < 0) return null
        val id = segments.getOrNull(details + 1)?.trim().orEmpty()
        if (id.isEmpty() || id.contains(' ')) return null
        return id
    }

    /** A URL dos metadados do item. */
    fun archiveMetadataUrl(id: String): String = "https://archive.org/metadata/$id"

    /**
     * O melhor arquivo de áudio do item, com a URL de download.
     *
     * **A forma real do metadado.** O Internet Archive guarda o arquivo que o autor
     * publicou e as versões derivadas; e o campo `original` de um arquivo **derivado** é o
     * nome do arquivo de onde ele veio (`"original": "AcousticPodcast.mp3"`). Ou seja: o
     * `original` **não** é uma flag "true", é o nome do arquivo-base — e o arquivo que
     * **não** tem `original` é o de melhor qualidade. A 64 kbps aponta para a VBR, e a VBR
     * não aponta para ninguém.
     *
     * O filtro também é pela **extensão**, não pelo `format`: o item traz um derivado
     * "64Kbps MP3 **ZIP**", que tem "MP3" no rótulo e é um zip. Escolher o maior sem filtrar
     * o zip era download de um arquivo que não toca.
     */
    fun archiveAudio(id: String, json: String): Pair<String, String>? {
        val files = runCatching { JSONObject(json).optJSONArray("files") }.getOrNull() ?: return null
        val audio = mutableListOf<JSONObject>()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val name = file.optString("name", "")
            if (name.isBlank()) continue
            if (name.substringAfterLast('.', "").lowercase() !in AUDIO_EXTENSIONS) continue
            audio += file
        }
        if (audio.isEmpty()) return null
        // Quem não aponta para ninguém é o arquivo de melhor qualidade; o resto é derivado.
        val notDerived = audio.filter { it.optString("original", "").isBlank() }
        val pool = if (notDerived.isNotEmpty()) notDerived else audio
        val chosen = pool.maxByOrNull { it.optLong("size", 0L) } ?: return null
        val name = chosen.optString("name", "")
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val title = chosen.optString("title", "").ifBlank { name.substringBeforeLast('.') }
        return "https://archive.org/download/$id/$encoded" to title
    }

    /** As extensões que o app sabe tocar. Zip e html não entram, mesmo com "MP3" no rótulo. */
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav")

}
