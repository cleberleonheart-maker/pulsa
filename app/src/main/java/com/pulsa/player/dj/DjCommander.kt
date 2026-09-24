package com.pulsa.player.dj

object DjCommander {

    // Conectivos de comando encadeado: "toca X e depois pausa", "próxima, entao curti".
    private val CHAIN_SEPS = arrayOf(
        " e depois ", " e em seguida ", " e entao ", " e logo ",
        " depois ", " em seguida ", " entao ", " logo "
    )

    /** Divide um comando encadeado em até duas partes; vazio quando não há conectivo. */
    fun chain(norm: String): List<String> {
        for (sep in CHAIN_SEPS) {
            val i = norm.indexOf(sep)
            if (i >= 0) {
                val before = norm.substring(0, i).trim()
                val after = norm.substring(i + sep.length).trim()
                if (before.isEmpty() || after.isEmpty()) continue
                return listOf(before, after)
            }
        }
        return emptyList()
    }

    fun norm(text: String): String {
        return text.lowercase()
            .replace('á', 'a').replace('à', 'a').replace('â', 'a').replace('ã', 'a').replace('ä', 'a')
            .replace('é', 'e').replace('è', 'e').replace('ê', 'e').replace('ë', 'e')
            .replace('í', 'i').replace('ì', 'i').replace('î', 'i').replace('ï', 'i')
            .replace('ó', 'o').replace('ò', 'o').replace('ô', 'o').replace('õ', 'o').replace('ö', 'o')
            .replace('ú', 'u').replace('ù', 'u').replace('û', 'u').replace('ü', 'u')
            .replace('ç', 'c')
            .trim()
    }

    // ----- Detecção de idioma da pergunta (pt/en/es) -----

    private val EN_MARKERS = listOf(
        "the ", " and ", " you", " your", " i ", " me ", " my ", " with ", " please",
        " thank", " thanks", " what", " who", " how", " which", " welcome",
        " can you", " could you", " would you", " are you", " do you", " is this",
        " play it", " play the", " next song", " wake up", " good morning",
        " good afternoon", " good evening", " good night", " louder", " quieter",
        " really", " just ", " yeah", " let's", " let me", " don't", " didn't"
    )

    private val ES_MARKERS = listOf(
        " por favor", " gracias", " hola", " buenos", " buenas", " qué", " cómo",
        " cuál", " quién", " dónde", " cuando", " puedes", " puede poner",
        " quiero", " necesito", " canción", " canciones", " siguiente",
        " anterior", " favorita", " favorito", " álbum", " artista",
        " volumen", " más fuerte", " más alto", " dime", " eres", " tú ",
        " tu nombre", " habla", " despierta", " cuántas", " cuántos",
        " una canción", " la música", " el volumen", " es tu"
    )

    /**
     * Heurística simples: conta marcadores de inglês e espanhol no texto cru.
     * Sem marcadores claros, assume português (padrão da Virgin).
     */
    fun languageOf(text: String): String {
        val t = " " + text.lowercase() + " "
        val en = EN_MARKERS.count { t.contains(it) }
        val es = ES_MARKERS.count { t.contains(it) }
        return when {
            en >= 2 && en > es && es == 0 -> "en"
            es >= 2 && es > en && en == 0 -> "es"
            en >= 1 && es == 0 -> "en"
            es >= 1 && en == 0 -> "es"
            else -> "pt"
        }
    }

    // Lê a intenção do usuário contornando a pontuacao/enj of the recognizer
    // (ex: "sim.", "Sim!", "sim, pode" -> afirmacao; "nao.", "nao!" -> negacao).
    private fun affirm(norm: String): Boolean =
        norm == "sim" || norm == "afirmativo" || norm == "confirmo" ||
            norm == "pode" || norm == "claro" || norm == "pode apagar" ||
            norm.contains("sim,") || norm.contains("sim ") || norm.endsWith("sim.") ||
            norm.endsWith("sim!") || norm.endsWith("sim?") ||
            norm.contains("pode apagar") || norm.contains("pode excluir") ||
            norm.contains("pode deleta") || norm.contains("pode apaga") ||
            norm.contains("pode exclui") || norm.contains("pode remove") ||
            norm.contains("confirma") || norm.contains("confirmo")

    private fun deny(norm: String): Boolean =
        norm == "nao" || norm == "nao quero" || norm == "não" || norm == "nao!" ||
            norm.contains("nao,") || norm.contains("nao ") || norm.endsWith("nao.") ||
            norm.contains("cancel") || norm.contains("esquece") || norm.contains("cala a boca")

    fun hasWake(norm: String): Boolean =
        norm.contains("virgin") || norm.contains("virgem") ||
            norm.contains("virgene") || norm.contains("vargin")

    fun onlyArtist(norm: String): String? {
        val markers = listOf(
            "toca so ", "toque so ", "tocar so ", "toca somente ", "toque somente ",
            "tocar somente ", "somente ", "apenas "
        )
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length).trim().trim(',', '.', '!', '?', ' ')
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    fun mixArtist(norm: String): String? {
        val markers = listOf("mistura com ", "misturar com ", "mixar com ", "mixa com ")
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length).trim().trim(',', '.', '!', '?', ' ')
                if (rest.isNotEmpty()) return rest
            }
        }
        return null
    }

    private fun sleepMatch(norm: String): Boolean =
        listOf("dormir", "dorme", "dormi", "sono", "relaxar", "relaxa", "calma", "calmo",
            "descansar", "acalma", "tranquila", "tranquilo", "modo sono").any { norm.contains(it) }

    private fun monthFavsMatch(norm: String): Boolean {
        val ptEsPeriod = norm.contains("do mes") || norm.contains("desse mes") ||
            norm.contains("deste mes") || norm.contains("este mes") || norm.contains("del mes")
        val likedWord = norm.contains("favorit") || norm.contains("curtid") ||
            norm.contains("gost") || norm.contains("lik") || norm.contains("amei")
        return (ptEsPeriod || norm.contains("month")) && likedWord
    }

    private fun moodWildMatch(norm: String): Boolean =
        listOf("bombar", "bombra", "anima", "animar", "acelera", "acelerar",
            "festa", "agit", "empolg", "firmeza", "pancadao").any { norm.contains(it) }

    private fun resumeMatch(norm: String): Boolean =
        norm.contains("de onde parou") || norm.contains("da onde parou") ||
            norm.contains("de onde eu parei") || norm.contains("onde parei") ||
            norm.contains("onde eu parei") ||
            norm.contains("volta pra musica") || norm.contains("volta a musica") ||
            norm.contains("voltar a musica") ||
            norm.contains("volta pra tocar") || norm.contains("volta a tocar") ||
            norm.contains("voltar a tocar") ||
            norm.contains("volta de onde") || norm.contains("voltar de onde") ||
            norm.contains("retoma") || norm.contains("retomar") ||
            norm.contains("recomeca") || norm.contains("recomecar")

    private fun playedYesterdayMatch(norm: String): Boolean =
        norm.contains("que toquei ontem") || norm.contains("toquei ontem") ||
            norm.contains("cantei ontem") || norm.contains("ouvi ontem") ||
            (norm.contains("ontem") && (norm.contains("toquei") || norm.contains("mudei"))) ||
            norm.contains("o que toquei") || norm.contains("o que eu toquei")

    private fun dailySetMatch(norm: String): Boolean =
        norm.contains("set do dia") || norm.contains("set de hoje") ||
            norm.contains("set de hj") || norm.contains("set diario") ||
            norm.contains("mix do dia") || norm.contains("mix de hoje") ||
            norm.contains("playlist do dia") || norm.contains("playlist de hoje") ||
            norm.contains("set of the day") || norm.contains("daily set") ||
            norm.contains("playlist of the day") || norm.contains("todays set") ||
            norm.contains("set del dia") || norm.contains("set de hoy") ||
            norm.contains("playlist del dia") || norm.contains("playlist de hoy")

    private fun countMatch(norm: String): Boolean {
        val amount = listOf("quantas", "quantos", "quanta", "numero de", "total de",
            "conta as", "conte as", "conte os", "conte as", "how many", "count my",
            "count the", "cuantas", "cuantos", "numero de canciones")
        val musicWord = listOf("musica", "mudica", "faixa", "faixas", "biblioteca",
            "aparelho", "song", "songs", "track", "tracks", "cancion", "canciones")
        return amount.any { norm.contains(it) } && musicWord.any { norm.contains(it) }
    }

    private fun dedicateMatch(norm: String): Boolean = norm.contains("dedic")

    fun dedicatee(norm: String): String? {
        val markers = listOf(
            "dedica para ", "dedicar para ", "dedica pra ", "dedicar pra ",
            "dedico para ", "dedique para ", "dedica a ", "dedique a ",
            "dedicate to ", "dedica pa ", "dedicada para ", "dedicada a "
        )
        for (m in markers) {
            val i = norm.indexOf(m)
            if (i >= 0) {
                val rest = norm.substring(i + m.length)
                    .replace("uma musica", "").replace("una cancion", "")
                    .replace("a song", "").replace("a musica", "")
                    .trim().trim(',', '.', '!', '?', ' ')
                if (rest.length >= 2) return rest
            }
        }
        return null
    }

    private fun memoryKey(norm: String): Boolean =
        listOf("numero", "telefone", "whats", "zap", "watss", "bluetooth", "bitu",
            "fone", "contato", "ligar").any { norm.contains(it) }

    private fun hasPhoneDigits(norm: String): Boolean {
        if (norm.filter { it.isDigit() }.length in 8..15) return true
        return Regex("""[0-9]+(?:\s*-?\s*[0-9]+)*""").findAll(norm).any {
            it.value.filter { c -> c.isDigit() }.length in 8..13
        }
    }

    private fun memorySave(norm: String): Boolean {
        if (!memoryKey(norm)) return false
        if (hasPhoneDigits(norm)) return true
        if (norm.contains("qual") || norm.contains("lembra")) return false
        return norm.contains("bluetooth") || norm.contains("bitu") || norm.contains("fone")
    }

    private fun memoryRecall(norm: String): Boolean {
        if (!memoryKey(norm)) return false
        if (hasPhoneDigits(norm)) return false
        return norm.contains("qual") || norm.contains("meu") || norm.contains("minha") ||
            norm.contains("lembra")
    }

    // ----- Controle do som ambiente por voz -----

    data class AmbientVol(val up: Boolean)

    private val AMBIENT_WORDS = listOf(
        "ambiente", "som ambiente",
        "chuva", "chuvinha", "torocó", "toroco", "ocean", "oceano", "mar",
        "floresta", "mata", "vento", "noite",
        "trovoada", "tempestade", "storm", "trovao", "tromba d água", "tromba de agua",
        "fogueira", "lareira", "fire", "fogao a lenha",
        "riacho", "rio", "cachoeira", "queda d agua", "river",
        "passaros", "passarinhos", "manha", "pajaro", "birds",
        "ruido branco", "ruido branca", "white", "branco",
        "pink", "rosa", "brown", "marrom"
    )

    private val VOL_UP_WORDS = listOf(
        "mais alta", "mais alto", "aumenta", "aumentar", "aumente",
        "sobe", "subir", "aumenta o", "mais volume", "deixa mais alto", "deixa mais alta"
    )

    private val VOL_DOWN_WORDS = listOf(
        "mais baixa", "mais baixo", "diminui", "diminuir", "diminua",
        "reduz", "reduza", "abaixa", "abaixar", "menos volume",
        "deixa mais baixo", "deixa mais baixa"
    )

    /** "virgi, chuva mais alta" → sobe o som ambiente; "abaixa o oceano" → desce. */
    fun ambientVolume(norm: String): AmbientVol? {
        if (!AMBIENT_WORDS.any { norm.contains(it) }) return null
        val up = VOL_UP_WORDS.any { norm.contains(it) }
        val down = VOL_DOWN_WORDS.any { norm.contains(it) }
        if (up == down) return null
        return AmbientVol(up = up)
    }

    // ----- Playlist dinâmica com regras faladas ("rock que nao toco ha 2 meses") -----

    data class DynQuery(
        val genres: List<String>,
        val maxAgeDays: Int?,
        val playsLessThan: Int?,
        val skipsLessThan: Int?,
        val favoritesOnly: Boolean
    ) {
        val hasRule: Boolean
            get() = maxAgeDays != null || playsLessThan != null || skipsLessThan != null || favoritesOnly
    }

    private const val DEFAULT_AGE_DAYS = 30
    private const val DEFAULT_LOW_COUNT = 4

    private val GENRES = listOf(
        "sertanejo", "pagode", "samba", "choro", "funk", "rock", "pop rock", "metal",
        "punk", "pop", "mpb", "forro", "axe", "folk", "indie", "jazz", "blues", "rap",
        "hip hop", "trap", "reggae", "eletronica", "clasica", "classica", "kpop",
        "instrumental", "country", "gospel", "bossa nova", "soul", "rnb", "disco", "dance"
    )

    private val PLAYLIST_INTENT = listOf(
        "toca", "toque", "tocar", "monta", "montar", "cria", "criar", "fila", "play",
        "mixa", "mix de", "mistura", "sobe", "so ", "somente", "apenas"
    )

    private val NUM_WORD = "(?:\\d+|uma|um|duas|dois|tres|quatro|cinco|seis|sete|oito|nove|dez)"

    private fun numOf(s: String): Int = when (s) {
        "um", "uma" -> 1
        "dois", "duas" -> 2
        "tres" -> 3
        "quatro" -> 4
        "cinco" -> 5
        "seis" -> 6
        "sete" -> 7
        "oito" -> 8
        "nove" -> 9
        "dez" -> 10
        else -> s.toIntOrNull() ?: 1
    }

    private fun daysOf(unit: String, n: Int): Int = when (unit) {
        "mes", "meses" -> n * 30
        "semana", "semanas" -> n * 7
        "ano", "anos" -> n * 365
        else -> n
    }

    /** Tokens de gênero presentes (deduplica: "pop" sai quando "pop rock" já casou). */
    private fun genresOf(norm: String): List<String> {
        val found = GENRES.filter { it.length >= 3 && norm.contains(it) }
        return found.filterNot { a -> found.any { b -> b != a && a in b } }
    }

    /** Gênero falado vs. tag do MediaStore: compara sem acentos/espaços/hífens. */
    fun matchesGenre(tag: String?, keyword: String): Boolean {
        if (tag == null) return false
        val t = norm(tag).replace(Regex("[^a-z0-9]"), "")
        val k = norm(keyword).replace(Regex("[^a-z0-9]"), "")
        return t.isEmpty() || k.isEmpty() || t.contains(k) || k.contains(t)
    }

    fun dynamicQuery(norm: String): DynQuery? {
        val genres = genresOf(norm)

        var maxAge: Int? = null
        val dur = Regex("(ha|faz)\\s+($NUM_WORD)\\s+(meses|mes|semanas|semana|dias|dia|anos|ano)").find(norm)
        if (dur != null) {
            maxAge = daysOf(dur.groupValues[3], numOf(dur.groupValues[2]))
        } else if (Regex("nao\\s+(toco|toquei|ouvi|ouco|cantei|escutei|tenho tocado|ouca)").containsMatchIn(norm)) {
            maxAge = DEFAULT_AGE_DAYS
        }

        var playsLess: Int? = null
        Regex("(?:toquei|tocou|ouvi|ouco|cantei|escutei)\\s+menos de\\s*($NUM_WORD)\\s*vezes?").find(norm)
            ?.let { playsLess = numOf(it.groupValues[1]) }
        if (playsLess == null &&
            Regex("(?:toquei|tocou|ouvi|ouco|cantei|escutei)\\s+pouco").containsMatchIn(norm)
        ) playsLess = DEFAULT_LOW_COUNT

        var skipsLess: Int? = null
        Regex("(?:pulei|pulou|pulava)\\s+menos de\\s*($NUM_WORD)\\s*vezes?").find(norm)
            ?.let { skipsLess = numOf(it.groupValues[1]) }
        if (skipsLess == null && Regex("(?:pulei|pulou)\\s+pouco").containsMatchIn(norm)
        ) skipsLess = DEFAULT_LOW_COUNT

        val favoritesOnly = norm.contains("favorit")

        if (genres.isEmpty() && maxAge == null && playsLess == null && skipsLess == null && !favoritesOnly) {
            return null
        }
        val rule = maxAge != null || playsLess != null || skipsLess != null || favoritesOnly
        val intent = rule || PLAYLIST_INTENT.any { norm.contains(it) } || norm.trim() in genres
        if (!intent) return null
        return DynQuery(genres, maxAge, playsLess, skipsLess, favoritesOnly)
    }

    // ----- Som ambiente por palavra falada (modo para o alarme/cenas) -----

    /** "com chuva", "com trovoada", "com oceano" → identifica o modo do ambiente. */
    fun ambientModeOf(norm: String): String? {
        if (norm.contains("chuva") || norm.contains("chuvinha") || norm.contains("toroco") ||
            norm.contains("rain")
        ) return com.pulsa.player.audio.Ambient.RAIN
        if (norm.contains("trovoada") || norm.contains("tempestade") || norm.contains("trovao") ||
            norm.contains("storm")
        ) return com.pulsa.player.audio.Ambient.STORM
        if (norm.contains("oceano") || norm.contains("ocean") || norm.contains("mar")) return com.pulsa.player.audio.Ambient.OCEAN
        if (norm.contains("floresta") || norm.contains("mata") || norm.contains("forest")) return com.pulsa.player.audio.Ambient.FOREST
        if (norm.contains("vento") || norm.contains("wind")) return com.pulsa.player.audio.Ambient.WIND
        if (norm.contains("fogueira") || norm.contains("lareira") || norm.contains("fire")) return com.pulsa.player.audio.Ambient.FIRE
        if (norm.contains("riacho") || norm.contains("cachoeira") || norm.contains("queda d agua") ||
            norm.contains("river")
        ) return com.pulsa.player.audio.Ambient.RIVER
        if (norm.contains("passaro") || norm.contains("passarinho") || norm.contains("pajaro") ||
            norm.contains("bird")
        ) return com.pulsa.player.audio.Ambient.BIRDS
        if (norm.contains("ruido branco") || norm.contains("white") || norm.contains("branco")) return com.pulsa.player.audio.Ambient.WHITE
        if (norm.contains("pink") || norm.contains("rosa")) return com.pulsa.player.audio.Ambient.PINK
        if (norm.contains("brown") || norm.contains("marrom")) return com.pulsa.player.audio.Ambient.BROWN
        if (norm.contains("noite") && !norm.contains("da noite") && !norm.contains("de noite")) return com.pulsa.player.audio.Ambient.NIGHT
        return null
    }

    // ----- Rádio por cena ("toca pra malhar/estudar/viajar/dirigir") -----

    const val SCENE_MALHAR = "malhar"
    const val SCENE_ESTUDAR = "estudar"
    const val SCENE_VIAJAR = "viajar"
    const val SCENE_DIRIGIR = "dirigir"

    private val SCENE_WORDS = mapOf(
        SCENE_MALHAR to listOf("malhar", "malho", "treina", "treino", "academia", "workout", "musculacao", "correr", "corrida"),
        SCENE_ESTUDAR to listOf("estudar", "estuda", "estudo", "study"),
        SCENE_VIAJAR to listOf("viajar", "viaja", "estrada", "roadtrip", "road trip", "passeio"),
        SCENE_DIRIGIR to listOf("dirigir", "dirige", "dirigindo", "volante", "drive")
    )

    private val SCENE_PLAYLIST_INTENT = listOf(
        "toca", "toque", "tocar", "monta", "radio", "modo", "ativa", "play", "mixa", "da um"
    )

    /** "virgi, toca pra malhar" → SCENE_MALHAR; "modo estudo" → SCENE_ESTUDAR. */
    fun sceneQuery(norm: String): String? {
        val scene = SCENE_WORDS.entries.firstOrNull { (_, words) -> words.any { norm.contains(it) } }?.key
            ?: return null
        val intent = SCENE_PLAYLIST_INTENT.any { norm.contains(it) } || norm.contains("pra ")
        return if (intent) scene else null
    }

    // ----- Resumo semanal falado -----

    private fun weekMatch(norm: String): Boolean =
        (norm.contains("resumo") && norm.contains("semana")) ||
            norm.contains("o que ouvi essa semana") || norm.contains("o que eu ouvi essa semana") ||
            norm.contains("o que ouvi esta semana") || norm.contains("o que eu ouvi esta semana") ||
            norm.contains("como foi minha semana") || norm.contains("como foi a minha semana") ||
            norm.contains("quais foram as mais tocadas") || norm.contains("minha semana") ||
            norm.contains("weekly") || norm.contains("this week recap") ||
            norm.contains("resumen de la semana") || norm.contains("resumen semanal") ||
            norm.contains("como fue mi semana") || norm.contains("mi semana") ||
            norm.contains("semana passada")

    // ----- Despertador ("me acorda às 7h") + sleep timer ("para em 20 min") -----

    data class AlarmSpec(val hour: Int, val minute: Int, val ambient: String?)

    private val HOUR_WORDS = mapOf(
        "uma" to 1, "um" to 1, "duas" to 2, "dois" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10,
        "onze" to 11, "doze" to 12
    )

    private fun ambientAlarmMode(norm: String): String? {
        if (norm.contains("com chuva") || norm.contains("com a chuva") || norm.contains("com trovoes") ||
            norm.contains("com trovoada") || norm.contains("de chuva") || norm.contains("com oceano") ||
            norm.contains("com a floresta") || norm.contains("com passaros") || norm.contains("com noite")
        ) return ambientModeOf(norm)
        return null
    }

    private fun alarmTime(norm: String): Pair<Int, Int>? {
        // "7:30" / "07h15"
        Regex("""(\d{1,2})\s*[:h]\s*(\d{1,2})""").find(norm)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: return null
            val m = it.groupValues[2].toIntOrNull() ?: return null
            if (h > 23 || m > 59) return null
            return Pair(h, m)
        }
        // "às 7h", "as 9 horas", "7 horas"
        Regex("""(\d{1,2})\s*(?:h|hs|hrs|horas)\b""").find(norm)?.let {
            val h = it.groupValues[1].toIntOrNull() ?: return null
            if (h > 23) return null
            return Pair(h, minuteOf(norm))
        }
        // por extenso: "sete horas", "acordas às sete e meia"
        if (norm.contains("horas") || norm.contains("hora") || norm.contains("e meia") ||
            norm.contains("e quinze") || norm.contains("e quarenta")
        ) {
            HOUR_WORDS.forEach { (word, value) ->
                if (Regex("""\b$word\b""").containsMatchIn(norm)) return Pair(value, minuteOf(norm))
            }
        }
        return null
    }

    private fun minuteOf(norm: String): Int = when {
        norm.contains("e meia") -> 30
        norm.contains("e quinze") -> 15
        norm.contains("e quarenta") -> 45
        else -> 0
    }

    private fun isAfternoonNorm(norm: String): Boolean =
        norm.contains("da tarde") || norm.contains("de tarde") ||
            norm.contains("da noite") || norm.contains("de noite")

    /** "virgi, me acorda às 7h com chuva" → AlarmSpec(7, 0, "rain"). */
    fun alarmQuery(norm: String): AlarmSpec? {
        val gate = norm.contains("acorda") || norm.contains("acorde") || norm.contains("acordar") ||
            norm.contains("desperta") || norm.contains("despertad") || norm.contains("alarme") ||
            norm.contains("me acorde") || norm.contains("wake me") || norm.contains("alarm at") ||
            norm.contains("despierta") || norm.contains("alarma")
        if (!gate) return null
        val t = alarmTime(norm) ?: return null
        var hour = t.first
        if (isAfternoonNorm(norm) && hour in 1..11) hour += 12
        return AlarmSpec(hour, t.second, ambientAlarmMode(norm))
    }

    /** "para em 20 min", "pausa em meia hora" → minutos (1..720). */
    fun sleepTimerQuery(norm: String): Int? {
        if (!(norm.contains("para em") || norm.contains("parar em") || norm.contains("pausa em") ||
                norm.contains("para daqui a") || norm.contains("parar daqui a"))
        ) return null
        Regex("""(?:para|parar|pausa)\s+(?:em\s+|daqui a\s+)?(\d+)\s*(min|minuto|minutos|hora|horas)\b""")
            .find(norm)?.let {
                val n = it.groupValues[1].toIntOrNull() ?: return null
                val minutes = if (it.groupValues[2].startsWith("h")) n * 60 else n
                return minutes.coerceIn(1, 720)
            }
        Regex("""(?:para|parar|pausa)\s+em\s+(meia hora|uma hora|hora e meia)\b""").find(norm)?.let {
            return when (it.groupValues[1]) {
                "meia hora" -> 30
                "uma hora" -> 60
                else -> 90
            }
        }
        return null
    }

    // ----- Túnel do tempo por década ("anos 80", "década de 90", "anos 2000") -----

    private val DECADE_WORD_YEARS = mapOf(
        "cinquenta" to 1950,
        "sessenta" to 1960,
        "setenta" to 1970,
        "oitenta" to 1980,
        "noventa" to 1990
    )

    /** "virgi, toca anos 80" → 1980; "década de 90" → 1990; "anos 2000" → 2000. */
    fun decadeQuery(norm: String): Int? {
        Regex("(?:anos?|decad[ao]?)\\s+(?:de\\s*)?(\\d{4})").find(norm)?.let {
            return it.groupValues[1].toIntOrNull()
        }
        Regex("(?:anos?|decad[ao]?)\\s+(?:de\\s*)?('?\\d{2})\\s*s?\\b").find(norm)?.let {
            val n = it.groupValues[1].replace("'", "").toIntOrNull() ?: return null
            return if (n in 41..99) 1900 + n else 2000 + n
        }
        DECADE_WORD_YEARS.forEach { (word, base) ->
            if (norm.contains("anos $word") || norm.contains("decada de $word")) return base
        }
        return null
    }

    fun action(norm: String): String? = when {
        ambientVolume(norm) != null -> "ambient_vol"
        sceneQuery(norm) != null -> "scene"
        weekMatch(norm) -> "weekly"
        sleepTimerQuery(norm) != null -> "sleeptimer"
        (norm.contains("cancel") || norm.contains("desliga") || norm.contains("remove") ||
            norm.contains("apaga") || norm.contains("delete") || norm.contains("clear")) &&
            (norm.contains("alarme") || norm.contains("alarm") || norm.contains("despertad")) -> "alarm_cancel"
        alarmQuery(norm) != null -> "alarm"
        dynamicQuery(norm) != null -> "dynq"
        decadeQuery(norm) != null -> "decade"
        countMatch(norm) -> "count"
        dailySetMatch(norm) -> "daily_set"
        memorySave(norm) -> "memory_save"
        memoryRecall(norm) -> "memory_recall"
        moodWildMatch(norm) -> "mood_wild"
        sleepMatch(norm) -> "sleep"
        monthFavsMatch(norm) -> "month_favs"
        norm.contains("repete essa") || norm.contains("repete a musica") ||
            norm.contains("repetir essa") || norm.contains("repita essa") -> "repeat"
        onlyArtist(norm) != null -> "only"
        mixArtist(norm) != null -> "mixwith"
        norm.contains("mix") || norm.contains("mistura") || norm.contains("mixa") -> "mix"
        norm.contains("odia") || norm.contains("odeio") || norm.contains("nao gostei") -> "dislike"
        norm.contains("pula") || norm.contains("pular") || norm.contains("pule") ||
            norm.contains("skip") -> "skip"
        norm.contains("proxima") || norm.contains("passa") || norm.contains("avanc") -> "next"
        resumeMatch(norm) -> "resume"
        playedYesterdayMatch(norm) -> "yesterday"
        norm.contains("anterior") || norm.contains("volta") || norm.contains("voltar") -> "prev"
        norm.contains("pausa") || norm.contains("pausar") || norm.contains("parar") ||
            norm.contains("pare") || norm.contains("stop") ||
            norm == "para" || norm.endsWith("para") ||
            norm.contains("para a musica") || norm.contains("para o som") ||
            norm.contains("para de tocar") || norm.contains("para agora") -> "pause"
        norm.contains("toca") || norm.contains("toque") || norm.contains("continua") -> "play"
        norm.contains("favorit") || norm.contains("curti") || norm.contains("gostei") ||
            norm.contains("amei") -> "fav"
        norm.contains("reconhec") || norm.contains("identif") || norm.contains("que musica") ||
            norm.contains("o que esta") || norm.contains("o que ta") -> "recognize"
        norm.contains("visualizador") || norm.contains("visualize") || norm.contains("barrinhas") ||
            norm.contains("barras de") || norm.contains("visualizer") -> "visualizer"
        norm.contains("skin") || norm.contains("tema") ||
            norm.contains("shader") || norm.contains("fundo") || norm.contains("efeito visual") ||
            norm.contains("aurora") || norm.contains("particula") || norm.contains("neon") -> "skin"
        norm.contains("karaoke") || norm.contains("karoke") || norm.contains("sem vocal") ||
            norm.contains("tira o vocal") || norm.contains("instrumental") -> "karaoke"
        norm.contains("stem") || norm.contains("separ") -> "stems"
        norm.contains("buscar") || norm.contains("busca") || norm.contains("novidade") ||
            norm.contains("importar") || norm.contains("escanear") || norm.contains("scan") -> "scan"
        norm.contains("repetid") || norm.contains("repitid") || norm.contains("repeti") ||
            norm.contains("duplic") || norm.contains("copias") || norm.contains("iguais") -> "duplicates"
        norm.contains("pendrive") || norm.contains("pendr") || norm.contains("pen drive") ||
            norm.contains("pen-drive") || norm.contains("pen d") || norm.contains("cartao") ||
            norm.contains("usb") || norm.contains("memoria") -> "pendrive"
        norm.contains("confirm") || affirm(norm) || norm == "pode" ||
            norm.contains("pode apagar") || norm.contains("pode excluir") ||
            norm.contains("pode deleta") -> "confirm"
        norm.contains("cancel") || norm.contains("esquece") || norm == "nao" -> "cancel"
        norm.contains("delet") || norm.contains("apag") || norm.contains("exclui") ||
            norm.contains("remove") -> "delete"
        norm.contains("qual") || norm.contains("essa") || norm.contains("tocando") -> "info"
        norm.contains("suger") || norm.contains("sugest") || norm.contains("recomend") ||
            norm.contains("indica uma") || norm.contains("o que voce tocaria") ||
            norm.contains("o que vc tocaria") -> "suggest"
        norm.contains("quem e voce") || norm.contains("quem e vc") || norm.contains("quem voce e") ||
            norm.contains("se apresenta") || norm.contains("se apresente") || norm.contains("conte sua historia") ||
            norm.contains("conta sua historia") || norm.contains("fale de voce") || norm.contains("fala de voce") ||
            norm.contains("o que voce faz") || norm.contains("voce e quem") ||
            norm.contains("como voce nasceu") || norm.contains("sua identidade") -> "identity"
        norm.contains("obrigad") || norm.contains("valeu") -> "thanks"
        dedicateMatch(norm) -> "dedicate"
        norm.contains("oi") || norm.contains("ola") -> "hello"
        else -> null
    }
}
