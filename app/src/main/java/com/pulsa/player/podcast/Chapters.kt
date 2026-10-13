package com.pulsa.player.podcast

/**
 * Um capítulo de episódio: o instante de começo e o rótulo que o feed deu a ele.
 *
 * Vem de graça na **descrição** do episódio, que quase todo podcast publica com o índice
 * "00:00 Abertura / 03:20 O assunto / 41:10 Encerramento". Não há campo de capítulo no modelo
 * de feed que o parser lê, e o `<podcast:chapters>` (JSON à parte) exigiria uma segunda
 * requisição por episódio — caro e raríssimo. Ler os minutos do texto que já está salvo cobre
 * a maioria dos feeds reais e não custa rede nem coluna nova no banco.
 */
data class Chapter(val startMs: Long, val title: String)

/**
 * Extrai os capítulos da descrição do episódio.
 *
 * A descrição chega aqui já sem HTML ([FeedParser.stripHtml]), então as marcações de tempo
 * sobrevivem como texto puro — inclusive quando as tags viraram espaços e as quebras de linha
 * sumiram. Por isso o corte não é por linha: procura-se cada `mm:ss`/`h:mm:ss` e o rótulo é o
 * que vem até o próximo carimbo.
 *
 * Só é lista de capítulos se houver **pelo menos dois** carimbos e eles **avançarem no tempo**.
 * Uma hora solta no meio da prosa ("às 2:30 da tarde") é comum demais para virar capítulo; a
 * exigência de ordem crescente descarta esses falsos positivos sem precisar entender a frase.
 */
object Chapters {

    private val STAMP = Regex("(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)")

    fun parse(description: String): List<Chapter> {
        if (description.isBlank()) return emptyList()
        val found = ArrayList<Pair<IntRange, Long>>()
        for (m in STAMP.findAll(description)) {
            val ms = toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]) ?: continue
            found.add(m.range to ms)
        }
        if (found.size < 2) return emptyList()

        val chapters = found.mapIndexed { i, (range, ms) ->
            val end = found.getOrNull(i + 1)?.first?.first ?: description.length
            Chapter(ms, cleanTitle(description.substring(range.last + 1, end)))
        }
        // A ordem no texto não garante ordem no tempo (o feed pode listar "extra" no fim).
        // Sem avanço monotônico, não é índice — é conversa.
        if (chapters.zipWithNext().any { (a, b) -> b.startMs < a.startMs }) return emptyList()
        return chapters
    }

    private fun toMs(g1: String, g2: String, g3: String): Long? {
        // Com hora: "h:mm:ss" (g1 hora, g2 minuto, g3 segundo). Sem hora: "mm:ss".
        if (g3.isNotEmpty()) {
            val h = g1.toIntOrNull() ?: return null
            val m = g2.toIntOrNull() ?: return null
            val s = g3.toIntOrNull() ?: return null
            if (m > 59 || s > 59) return null
            return (h * 3600L + m * 60L + s) * 1000L
        }
        val mm = g1.toIntOrNull() ?: return null
        val ss = g2.toIntOrNull() ?: return null
        if (mm > 59 || ss > 59) return null
        return (mm * 60L + ss) * 1000L
    }

    /** "m:ss" até uma hora, "h:mm:ss" daí pra frente — o jeito que o ouvinte lê capítulo. */
    fun formatTime(ms: Long): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Tira separadores e sobra só o rótulo; corta na primeira quebra e limita o tamanho. */
    private fun cleanTitle(raw: String): String {
        val firstLine = raw.substringBefore('\n')
        val out = firstLine.replace(LEAD, "").replace(TAIL, "").take(100).trim()
        return out.ifBlank { "Sem título" }
    }

    // Separam o carimbo do rótulo: "00:00 - Tema", "(05:00) Tema", "• Tema". Ancorados nas
    // pontas e com espaço na classe, porque o stripHtml deixa as sobras espaçadas e o rótulo
    // pode começar por "- " ou ") ".
    private val LEAD = Regex("^[\\s\\-\\u2013\\u2014\\u2022\\u00b7|:.,()\\[\\]]+")
    private val TAIL = Regex("[\\s\\-\\u2013\\u2014\\u2022\\u00b7|:.,()\\[\\]]+$")
}
