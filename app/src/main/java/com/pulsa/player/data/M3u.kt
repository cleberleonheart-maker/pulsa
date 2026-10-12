package com.pulsa.player.data

import android.content.Context
import com.pulsa.player.dj.DjCommander
import com.pulsa.player.model.Song

/**
 * Importar/exportar playlists no formato M3U (F5).
 *
 * M3U é o "denominador comum" entre players: qualquer um lê e escreve. O Pulsa já tinha backup
 * próprio (`PlaylistBackup`, JSON), que resolve **entre aparelhos** por caminho, mas só o Pulsa
 * lê. O `.m3u` é para levar uma playlist para fora (outro player, um chat) e trazer de volta.
 *
 * **O parse e o serialize são puros** [Track]/[parse]/[serialize] — sem Context, sem banco,
 * sem MediaStore. É a única parte testável de verdade (e onde mora a tolerância a arquivo de
 * outros players, que é onde o formato varia). [resolve] é o pedaço impuro: casa as faixas com
 * a biblioteca deste aparelho.
 *
 * **Casar só por caminho não basta.** Um `.m3u` vindo de outro celular traz
 * `/storage/emulated/0/Music/foo.mp3`, e o mesmo arquivo aqui pode estar em
 * `/storage/emulated/0/Download/foo.mp3` — o caminho não resolve de volta. Por isso a
 * resolução cai, nesta ordem: caminho exato → "artista - título" do `#EXTINF` → título →
 * nome do arquivo. Cada degrau é mais frouxo que o anterior, e o que não casa em nenhum é
 * dito no relatório, nunca engolido.
 */
object M3u {

    /** Uma linha de faixa já normalizada: o que o `#EXTINF` dizia + o caminho que a segue. */
    data class Track(val path: String, val title: String = "", val artist: String = "")

    private const val EXTINF = "#EXTINF:"

    /**
     * Escreve a playlist no formato M3U estendido.
     *
     * `#EXTM3U` na primeira linha é o que marca "M3U estendido" e o que muitos players usam
     * para decidir se aceitam o arquivo; sem ele, alguns recusam. O `#EXTINF` leva os segundos
     * (o M3U pede segundos, não ms) e o cartaz "artista - título", que é a convenção de fato —
     * é o que dá para reler quando o caminho não existe mais.
     */
    fun serialize(songs: List<Song>): String {
        val sb = StringBuilder()
        sb.append("#EXTM3U").append('\n')
        songs.forEach { song ->
            val secs = (song.durationMs / 1000L).coerceAtLeast(0L)
            val artist = song.artist.trim()
            val title = song.title.trim()
            val label = when {
                artist.isNotEmpty() && title.isNotEmpty() -> "$artist - $title"
                title.isNotEmpty() -> title
                else -> song.path
            }
            sb.append(EXTINF).append(secs).append(',').append(label).append('\n')
            sb.append(song.path).append('\n')
        }
        return sb.toString()
    }

    /**
     * Lê um M3U. Tolerante de propósito: aceita CRLF, linhas em branco, comentários e um
     * `#EXTINF` órfão (sem caminho depois) que é simplesmente descartado. Um arquivo de
     * outro player é justamente onde essas variações aparecem.
     *
     * O caminho é a primeira linha que não é comentário e não está vazia. O `#EXTINF` que a
     * precede (se houver) fornece título/artista.
     */
    fun parse(text: String): List<Track> {
        val tracks = mutableListOf<Track>()
        var pendingTitle = ""
        var pendingArtist = ""
        var hasPending = false

        text.split('\n').forEach { raw ->
            val line = raw.trim().removeSuffix("\r").trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("#")) {
                if (line.startsWith(EXTINF)) {
                    val info = parseExtinf(line)
                    pendingArtist = info.first
                    pendingTitle = info.second
                    hasPending = true
                }
                return@forEach
            }
            // Linha de caminho. O caminho é o que o player abre; título/artista são só o
            // fallback para quando o caminho não existe neste aparelho.
            tracks += Track(
                path = line,
                title = if (hasPending) pendingTitle else "",
                artist = if (hasPending) pendingArtist else ""
            )
            pendingTitle = ""
            pendingArtist = ""
            hasPending = false
        }
        return tracks
    }

    /**
     * `#EXTINF:123,Artista - Título` → `("Artista", "Título")`.
     *
     * O separador é **a primeira vírgula**, não a última: títulos com vírgula ("Só eu, você")
     * são comuns, e cortar pela última picaria o título. O " - " que separa artista de título
     * é o primeiro; sem ele, tudo é título e o artista fica vazio (é o caso de `#EXTINF:0,Radio
     * Head`).
     */
    private fun parseExtinf(line: String): Pair<String, String> {
        val comma = line.indexOf(',')
        if (comma < 0) return "" to ""
        val display = line.substring(comma + 1).trim()
        if (display.isEmpty()) return "" to ""
        val dash = display.indexOf(" - ")
        if (dash < 0) return "" to display
        val artist = display.substring(0, dash).trim()
        val title = display.substring(dash + 3).trim()
        return artist to title
    }

    /** O que voltou da importação: as faixas que casaram e as que não casaram. */
    data class Resolved(val songs: List<Song>, val missing: List<Track>)

    /**
     * Casa as faixas do arquivo com a biblioteca deste aparelho.
     *
     * Uma varredura só da biblioteca serve o arquivo inteiro: procurar faixa a faixa faria N
     * varreduras do MediaStore para um arquivo com N faixas. O índice por caminho usa
     * [Library.songsByPaths] (lotes no MediaStore); os índices por texto usam [Library.allSongs],
     * que já está em memória noutros fluxos.
     *
     * A ordem dos degraus importa e está em [matchOne]. O caminho é o único que não pode dar
     * falso positivo; os degraus de texto são heurística e só entram para o que sobrou.
     */
    fun resolve(context: Context, tracks: List<Track>): Resolved {
        if (tracks.isEmpty()) return Resolved(emptyList(), emptyList())

        val byPath = Library.songsByPaths(context, tracks.map { it.path })
            .associateBy { it.path }

        val unmatched = mutableListOf<Track>()
        val matched = mutableListOf<Song>()
        // Os índices de texto só são montados se alguma faixa não casou por caminho: se o
        // arquivo foi exportado deste aparelho, todos os caminhos batem e a varredura
        // completa da biblioteca seria trabalho jogado fora.
        var index: TextIndex? = null

        tracks.forEach { track ->
            val direct = byPath[track.path]
            if (direct != null) {
                matched += direct
            } else {
                val idx = index ?: TextIndex.build(Library.allSongs(context)).also { index = it }
                val song = matchOne(track, idx)
                if (song != null) matched += song else unmatched += track
            }
        }
        return Resolved(matched, unmatched)
    }

    private fun matchOne(track: Track, index: TextIndex): Song? {
        val artist = track.artist.trim()
        val title = track.title.trim()
        if (artist.isNotEmpty() && title.isNotEmpty()) {
            index.byArtistTitle[DjCommander.norm("$artist - $title")]?.let { return it }
        }
        if (title.isNotEmpty()) {
            index.byTitle[DjCommander.norm(title)]?.let { return it }
        }
        // Último degrau: o nome do arquivo. Um M3U sem `#EXTINF` (ou com um cartaz inútil)
        // ainda pode ser resolvido pelo nome, que é o que sobra quando só o caminho veio.
        val fileName = track.path.substringAfterLast('/').substringBefore('?')
        if (fileName.isNotEmpty()) {
            index.byFileName[DjCommander.norm(fileName)]?.let { return it }
        }
        return null
    }

    /** Índices de casamento por texto, montados uma vez por arquivo. */
    private class TextIndex(
        val byArtistTitle: Map<String, Song>,
        val byTitle: Map<String, Song>,
        val byFileName: Map<String, Song>
    ) {
        companion object {
            fun build(songs: List<Song>): TextIndex {
                val artistTitle = HashMap<String, Song>(songs.size)
                val title = HashMap<String, Song>(songs.size)
                val fileName = HashMap<String, Song>(songs.size)
                songs.forEach { song ->
                    artistTitle.putIfAbsent(
                        DjCommander.norm("${song.artist} - ${song.title}"), song
                    )
                    title.putIfAbsent(DjCommander.norm(song.title), song)
                    fileName.putIfAbsent(
                        DjCommander.norm(song.path.substringAfterLast('/')), song
                    )
                }
                return TextIndex(artistTitle, title, fileName)
            }
        }
    }
}
