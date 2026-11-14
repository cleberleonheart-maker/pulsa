package com.pulsa.player.dj
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object DjFacts {

    /**
     * A chave de comparação do artista: minúsculas, sem pontuação e **sem acento**.
     *
     * O acento importava porque as curiosidades locais moram num mapa com o nome escrito
     * ("chitãozinho e xororó") e a busca era exata: o "Beyoncé" da biblioteca (com acento)
     * não casava com a chave "beyonce", e o artista caía no MusicBrainz — que devolvia a
     * mesma frase para todo mundo. Sem acento dos dois lados, o fato local volta.
     */
    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val clean = name.trim().lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("\u0027", "")
            .replace(".", "")
            .replace(",", "")
            .replace("!", "")
            .replace("?", "")
            .replace("&", " e ")
            .replace("+", " e ")
            .replace("  ", " ")
            .trim()
        return java.text.Normalizer
            .normalize(clean, java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .trim()
    }

    /**
     * O fato local do artista.
     *
     * A busca por substring era frouxa demais: "silva" (a banda de forró) casava dentro de
     * "silvana" e de qualquer "João Silva", e a Virgin contava a mesma história para
     * artistas diferentes. Agora a palavra tem que aparecer inteira, e a frase completa
     * (com duas ou mais palavras) tem que aparecer junto.
     */
    fun curiosityFor(artistName: String): String? {
        val key = normalize(artistName)
        if (key.isEmpty()) return null
        FACTS_BY_KEY[key]?.let { return it }
        val words = key.split(" ").filter { it.isNotBlank() }
        return FACTS_BY_KEY.entries.firstOrNull { entry ->
            val parts = entry.key.split(" ").filter { it.isNotBlank() }
            when {
                parts.isEmpty() -> false
                parts.size == 1 -> words.any { it == parts[0] }
                else -> key.contains(entry.key)
            }
        }?.value
    }

    fun hasCuriosity(artistName: String): Boolean = curiosityFor(artistName) != null

    private const val REMOTE_COOLDOWN_MS = 1100L
    private const val CURIOSITY_MIN_MS = 5L * 60_000L
    private const val CURIOSITY_MAX_MS = 10L * 60_000L

    /** As frases possíveis por artista — a Virgin escolhe uma de relance. */
    private val remoteCache = HashMap<String, List<String>>()
    private val remoteInFlight = HashSet<String>()
    /** A última frase falada de cada artista, para não repetir a mesma. */
    private val lastSpoken = HashMap<String, String>()
    private var lastRemoteQueryMs = 0L
    private var lastCuriosityMs = 0L
    private var nextCuriosityDelayMs = 0L

    fun curiosityDue(): Boolean {
        if (nextCuriosityDelayMs == 0L) nextCuriosityDelayMs = randomDelayMs()
        return SystemClock.elapsedRealtime() - lastCuriosityMs >= nextCuriosityDelayMs
    }

    fun markCuriositySpoken() {
        lastCuriosityMs = SystemClock.elapsedRealtime()
        nextCuriosityDelayMs = randomDelayMs()
    }

    private fun randomDelayMs(): Long = CURIOSITY_MIN_MS + (Math.random() * (CURIOSITY_MAX_MS - CURIOSITY_MIN_MS + 1)).toLong()

    fun fetchRemoteCuriosity(context: Context, artistName: String, onResult: (String?) -> Unit) {
        val key = normalize(artistName)
        if (key.isEmpty()) {
            onResult(null)
            return
        }
        var cached: List<String>? = null
        var startFetch = false
        synchronized(remoteCache) {
            val known = remoteCache[key]
            if (known != null) {
                cached = known
            } else if (!remoteInFlight.contains(key)) {
                remoteInFlight.add(key)
                startFetch = true
            }
        }
        val known = cached
        if (known != null) {
            onResult(pickFact(key, known))
            return
        }
        if (!startFetch) {
            onResult(null)
            return
        }
        ThreadPool.postNetwork {
            val facts = musicBrainzFacts(key)
            synchronized(remoteCache) {
                remoteCache[key] = facts
                remoteInFlight.remove(key)
            }
            ThreadPool.onUi { onResult(pickFact(key, facts)) }
        }
    }

    /**
     * Escolhe a frase da vez — sem repetir a última do mesmo artista.
     *
     * O MusicBrainz devolve sempre os mesmos campos para o mesmo artista, então a frase
     * montada era sempre igual ("Segundo o MusicBrainz, X surgiu em Y e está na ativa desde
     * Z"), com o nome trocado e nada mais. Com várias frases possíveis — e a última de fora
     * do sorteio — a mesma artista rende curiosidades diferentes.
     */
    private fun pickFact(key: String, facts: List<String>): String? {
        if (facts.isEmpty()) return null
        val last = synchronized(lastSpoken) { lastSpoken[key] }
        val pool = if (facts.size > 1 && last != null) facts.filter { it != last } else facts
        val chosen = pool.random()
        synchronized(lastSpoken) { lastSpoken[key] = chosen }
        return chosen
    }

    /**
     * Os dados do artista no MusicBrainz, já limpos.
     *
     * Separado da frase porque a frase é a parte que muda: o banco devolve sempre os mesmos
     * campos para o mesmo artista, e é por isso que a curiosidade era sempre a mesma.
     */
    data class ArtistInfo(
        val name: String,
        val type: String? = null,
        val area: String? = null,
        val beginArea: String? = null,
        val year: Int? = null,
        val endYear: Int? = null,
        val genres: List<String> = emptyList(),
        val legalName: String? = null,
        val alias: String? = null
    )

    /**
     * Todas as frases que os dados do artista permitem.
     *
     * As frases evitam concordância de gênero ("artista solo", "aparece com") porque o
     * MusicBrainz traz "male"/"female" só para o gênero da pessoa — e a Virgin fala com
     * qualquer um dos dois.
     *
     * O lugar entra na frase já com a preposição certa: cidade é "em Juazeiro" e país é
     * "do Brasil" (e "dos Estados Unidos") — "começou em Brasil" era o jeito errado de
     * falar.
     */
    fun factsFor(info: ArtistInfo): List<String> {
        val out = ArrayList<String>()
        val name = info.name
        val genre = info.genres.take(2).joinToString(" e ").ifBlank { null }
        val kind = when (info.type) {
            "Person" -> "artista solo"
            "Group" -> "banda"
            "Orchestra" -> "orquestra"
            else -> null
        }
        val city = info.beginArea
        val cityDe = city?.let { "de $it" }
        val cityEm = city?.let { "em $it" }
        val country = info.area
        val countryDe = country?.let { COUNTRY_DE[it] ?: "de $it" }
        val countryEm = country?.let { COUNTRY_EM[it] ?: "em $it" }
        val year = info.year
        if (kind == "banda" && genre != null && city != null && year != null) {
            out += "Segundo o MusicBrainz, $name é uma banda de $genre que começou $cityEm em $year."
        }
        if (kind == "banda" && genre != null && country != null) {
            out += "Segundo o MusicBrainz, $name é uma banda de $genre $countryDe."
        }
        if (city != null && year != null) {
            out += "Segundo o MusicBrainz, $name começou $cityEm, em $year."
        }
        if (genre != null && cityDe != null) {
            out += "Segundo o MusicBrainz, $name aparece com $genre, e vem $cityDe."
        }
        if (year != null && countryDe != null) {
            out += "Segundo o MusicBrainz, $name está em atividade desde $year e vem $countryDe."
        }
        if (genre != null) {
            out += "Segundo o MusicBrainz, $name aparece com $genre."
        }
        if (info.legalName != null) {
            out += "Segundo o MusicBrainz, o nome completo de $name é ${info.legalName}."
        }
        if (info.alias != null) {
            out += "Segundo o MusicBrainz, $name também aparece como ${info.alias}."
        }
        if (info.endYear != null) {
            out += "Segundo o MusicBrainz, $name parou em ${info.endYear}."
        }
        if (kind != null) {
            out += "Segundo o MusicBrainz, $name aparece como $kind no MusicBrainz."
        }
        if (cityDe != null) {
            out += "Segundo o MusicBrainz, $name vem $cityDe."
        } else if (countryDe != null) {
            out += "Segundo o MusicBrainz, $name vem $countryDe."
        }
        if (year != null && countryEm != null) {
            out += "Segundo o MusicBrainz, $name começou $countryEm, em $year."
        }
        return out
    }

    private fun musicBrainzFacts(key: String): List<String> {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRemoteQueryMs < REMOTE_COOLDOWN_MS) return emptyList()
        lastRemoteQueryMs = now
        val query = URLEncoder.encode("artist:\"$key\"", "UTF-8")
        val url = "https://musicbrainz.org/ws/2/artist/?query=$query&limit=3&fmt=json"
        val json = httpGetJson(url) ?: return emptyList()
        val artists = runCatching { json.getJSONArray("artists") }.getOrNull() ?: return emptyList()
        var best: JSONObject? = null
        var bestScore = 0
        for (i in 0 until artists.length()) {
            val a = artists.getJSONObject(i)
            val s = runCatching { a.getInt("score") }.getOrDefault(0)
            if (s > bestScore) {
                bestScore = s
                best = a
            }
        }
        val a = best ?: return emptyList()
        val name = runCatching { a.getString("name") }.getOrNull() ?: return emptyList()
        val type = runCatching { a.getString("type") }.getOrNull()
        val area = runCatching { a.getJSONObject("area").getString("name") }.getOrNull()?.let { ptPlace(it) }
        val beginArea = runCatching { a.getJSONObject("begin-area").getString("name") }.getOrNull()?.let { ptPlace(it) }
        val life = runCatching { a.getJSONObject("life-span") }.getOrNull()
        val year = life?.optString("begin").orEmpty().take(4).toIntOrNull()
        val endYear = life?.optString("end").orEmpty().take(4).toIntOrNull()
        val aliases = aliasesOf(a, name)
        return factsFor(
            ArtistInfo(
                name = name,
                type = type,
                area = area,
                beginArea = beginArea,
                year = year,
                endYear = endYear,
                genres = genresOf(a),
                legalName = aliases.first,
                alias = aliases.second
            )
        )
    }

    /** Os gêneros mais marcados do artista ("latin pop", "axé"…). */
    private fun genresOf(a: JSONObject): List<String> {
        val tags = runCatching { a.getJSONArray("tags") }.getOrNull() ?: return emptyList()
        val scored = ArrayList<Pair<Int, String>>()
        for (i in 0 until tags.length()) {
            val t = tags.getJSONObject(i)
            val n = runCatching { t.getString("name") }.getOrNull() ?: continue
            val c = runCatching { t.getInt("count") }.getOrDefault(1)
            scored += c to n
        }
        return scored.sortedByDescending { it.first }.map { it.second }.take(2)
    }

    /**
     * Os apelidos do artista: o nome legal ("José Lima Sobrinho") e um outro nome de artista
     * ("Chitãozinho e Xororó") — as duas coisas que valem uma frase.
     */
    private fun aliasesOf(a: JSONObject, name: String): Pair<String?, String?> {
        val list = runCatching { a.getJSONArray("aliases") }.getOrNull() ?: return null to null
        var legal: String? = null
        var other: String? = null
        for (i in 0 until list.length()) {
            val al = list.getJSONObject(i)
            val n = runCatching { al.getString("name") }.getOrNull() ?: continue
            if (n.isBlank() || n.equals(name, ignoreCase = true)) continue
            val type = runCatching { al.getString("type") }.getOrNull()
            if (type == "Legal name" && legal == null) {
                legal = n
            } else if (type == "Artist name" && other == null) {
                other = n
            }
        }
        return legal to other
    }

    /**
     * O nome do país vem em inglês do MusicBrainz ("Brazil"), e a Virgin fala português:
     * "surgiu em Brazil" soava errado. Só os países que aparecem no dia a dia são trocados —
     * o resto fica como está, que ainda é melhor do que inventar.
     */
    private fun ptPlace(raw: String): String {
        val key = raw.trim().lowercase(Locale.ROOT)
        return COUNTRIES_PT[key] ?: raw
    }

    private val COUNTRIES_PT = mapOf(
        "brazil" to "Brasil",
        "united states" to "Estados Unidos",
        "united kingdom" to "Reino Unido",
        "mexico" to "México",
        "spain" to "Espanha",
        "argentina" to "Argentina",
        "france" to "França",
        "germany" to "Alemanha",
        "italy" to "Itália",
        "japan" to "Japão",
        "canada" to "Canadá",
        "portugal" to "Portugal",
        "sweden" to "Suécia",
        "norway" to "Noruega",
        "denmark" to "Dinamarca",
        "finland" to "Finlândia",
        "ireland" to "Irlanda",
        "australia" to "Austrália",
        "netherlands" to "Países Baixos",
        "belgium" to "Bélgica",
        "russia" to "Rússia",
        "south korea" to "Coreia do Sul",
        "colombia" to "Colômbia",
        "chile" to "Chile",
        "austria" to "Áustria",
        "switzerland" to "Suíça",
        "poland" to "Polônia",
        "turkey" to "Turquia",
        "greece" to "Grécia",
        "scotland" to "Escócia",
        "wales" to "País de Gales"
    )

    /** "vem **do** Brasil", "vem **da** França" — o artigo do país. */
    private val COUNTRY_DE = mapOf(
        "Brasil" to "do Brasil",
        "Estados Unidos" to "dos Estados Unidos",
        "Reino Unido" to "do Reino Unido",
        "México" to "do México",
        "Espanha" to "da Espanha",
        "Argentina" to "da Argentina",
        "França" to "da França",
        "Alemanha" to "da Alemanha",
        "Itália" to "da Itália",
        "Japão" to "do Japão",
        "Canadá" to "do Canadá",
        "Portugal" to "de Portugal",
        "Suécia" to "da Suécia",
        "Noruega" to "da Noruega",
        "Dinamarca" to "da Dinamarca",
        "Finlândia" to "da Finlândia",
        "Irlanda" to "da Irlanda",
        "Austrália" to "da Austrália",
        "Países Baixos" to "dos Países Baixos",
        "Bélgica" to "da Bélgica",
        "Rússia" to "da Rússia",
        "Coreia do Sul" to "da Coreia do Sul",
        "Colômbia" to "da Colômbia",
        "Chile" to "do Chile",
        "Áustria" to "da Áustria",
        "Suíça" to "da Suíça",
        "Polônia" to "da Polônia",
        "Turquia" to "da Turquia",
        "Grécia" to "da Grécia",
        "Escócia" to "da Escócia",
        "País de Gales" to "do País de Gales"
    )

    /** "começou **no** Brasil", "começou **na** França" — o lugar de onde começou. */
    private val COUNTRY_EM = COUNTRY_DE.mapValues { (_, phrase) ->
        when {
            phrase.startsWith("do ") -> "no " + phrase.removePrefix("do ")
            phrase.startsWith("dos ") -> "nos " + phrase.removePrefix("dos ")
            phrase.startsWith("da ") -> "na " + phrase.removePrefix("da ")
            phrase.startsWith("de ") -> "em " + phrase.removePrefix("de ")
            else -> phrase
        }
    }

    private fun httpGetJson(url: String): JSONObject? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = 2500
                conn.readTimeout = 3500
                conn.setRequestProperty(
                    "User-Agent",
                    "Pulsa/3.5 ( +https://cleberleonheart-maker.github.io/pulsaweb/ )"
                )
                conn.setRequestProperty("Accept", "application/json")
                if (conn.responseCode != 200) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                runCatching { JSONObject(body) }.getOrNull()
            } finally {
                runCatching { conn.disconnect() }
            }
        } catch (t: Throwable) {
            null
        }
    }

    fun leadIn(intensity: String): String {
        // Várias entradas por tom: a chamada fixa era metade do "sempre a mesma coisa".
        val options = when (intensity) {
            Settings.DJ_CALM -> listOf(
                "Um momento, antes da próxima:",
                "Ei, uma pausa rápida:",
                "Antes de trocar de faixa:"
            )
            Settings.DJ_WILD -> listOf(
                "Se liga nessa:",
                "Olha só:",
                "E antes da próxima:"
            )
            else -> listOf(
                "Antes de seguir, uma curiosidade:",
                "Uma curiosidade antes de trocar de faixa:",
                "Sabe o que eu andei descobrindo?"
            )
        }
        return options.random()
    }

    private val FACTS = mapOf(
        "backstreet boys" to "Os Backstreet Boys são da Flórida e venderam mais de 100 milhões de discos — na época em que nova música significava ir à loja farejar o CD.",
        "avicii" to "TIm Bergling, o Avicii, foi um dos maiores DJs do mundo antes dos 30 — e adorava misturar folk com eletrônica.",
        "cazuza" to "Cazuza compôs a maior parte do Barão Vermelho antes de carreira solo — e deixou letras eternas no Rock in Rio.",
        "bon jovi" to "Jon Bon Jovi é de Nova Jersey e virou lenda com músicas sobre estrada, amor e festa americana nos anos 80.",
        "calcinha preta" to "Garuota cutudisca: a banda de forró estilizado nasceu em Cruzeiro do Sul e lotou as rádios de FM do Brasil.",
        "chitãozinho e xororó" to "José Lima Sobrinho e Durval de Lima são irmãos de Astorga e gravaram na mesma fazenda até as raízes do sertão.",
        "coldplay" to "Com universos co-colaborando com artistas de todo o planeta — segredo deles é quase sempre recomeçar do zero a cada disco.",
        "david guetta" to "David Guetta começou tocando em clubes de Paris e ajudou a colocar o EDM no topo das paradas mundiais.",
        "ed sheeran" to "Ed Sheeran costuma compor no violão no porão de casa e já recusou propostas gigantes para manter o controle da própria carreira.",
        "elvis presley" to "O Rei do Rock, nascido em Memphis, popularizou o som que mudou a música para sempre nos anos 50.",
        "eminem" to "Eminem é de Detroit e virou o rapper mais vendido da história, especialista em rimar sem parar.",
        "green day" to "A garotada punk de Berkeley que transformou o mundo em um palco — surgiram de um porão e ficaram gigantes com o punk rock.",
        "guns n roses" to "Axl Rose escreveu Sweet Child O' Mine inspirado num poema que ele mesmo não sabia explicar.",
        "ivete sangalo" to "Ivete é baiana de Salvador e um dos maiores nomes da axé — a chamam de Ivete Sangalo, ícone absoluto do carnaval.",
        "jay z" to "Jay-Z é de Nova York e transformou o hip-hop em negócio de bilhões, sem nunca largar o microfone.",
        "kendrick lamar" to "Kendrick Lamar é de Compton e venceu o Pulitzer por um álbum de rap — coisa até então inédita.",
        "lady gaga" to "Lady Gaga compõe e arranha a voz para viver cada personagem — já ganhou Oscar e Grammy com a mesma intensidade.",
        "linkin park" to "Banda de Agoura Hills que uniu metal e eletrônica e quebrou o molde do rock dos anos 2000.",
        "maddona" to "A rainha do pop que reescreveu o pop quatro vezes e dominou as rádios por décadas com ousadia.",
        "mamonas assassinas" to "A banda de Guarulhos que estourou nos anos 90 com humor ácido e virou cultura pop instantânea.",
        "metallica" to "Metallica nasceu em 1981 em Los Angeles e em 1991 invadiu o mainstream com o famoso disco preto.",
        "michael jackson" to "O eterno Rei do Pop, cujo Thriller segue até hoje como um dos discos mais vendidos de todos os tempos.",
        "miley cyrus" to "Estrela dos anos 2000 que passou de ícone teen para artista do rock, sempre reinventando o próprio som.",
        "nirvana" to "Kurt Cobain e o trio de Aberdeen praticamente inventaram o grunge em Seattle nos anos 90.",
        "pearl jam" to "O Pearl Jam veio de Seattle e, sem querer, virou porta-voz de uma geração inteira.",
        "raimundos" to "Raimundos misturou paraíba e punk em Brasília nos anos 90 e virou trilha sonora dos que cresceram com eles.",
        "red hot chili peppers" to "O quarteto de Los Angeles que trouxe o funk direto para o rock, com baixos pegajosos desde o fim dos anos 80.",
        "shakira" to "Shakira é colombiana e escreveu a primeira música aos 8 anos — agora o mundo inteiro dança no mesmo ritmo.",
        "silva" to "A banda de forró que conquistou o Brasil cantando a vida de quem dança coladinho.",
        "skank" to "Skank carrega alguns dos maiores hinos do pop nacional com guitarras reggaeeiras de Belo Horizonte.",
        "snoop dogg" to "Snoop Dogg traz o swing californiano, mas todo mundo sabe que sua alma é um toque de puro G-funk de Long Beach.",
        "taylor swift" to "Taylor Swift gosta de rascunhar as letras em cadernos e esconder pistas nas capas dos discos.",
        "the beatles" to "Os Beatles gravaram o primeiro compacto em 1962 e pararam de tocar ao vivo em 1966 — o estúdio virou o novo palco.",
        "the rolling stones" to "Os Rolling Stones são a formação original que nunca desmanchou — mais de 60 anos de estrada.",
        "the weeknd" to "O Weeknd nasceu em Toronto e introduziu o R&B noturno nas paradas do mundo inteiro.",
        "tião carreiro e pardinho" to "Dupla raiz do sertanejo de criação — os dois vieram de Goiás e gravaram modas que viraram patrimônio.",
        "tokio hotel" to "A banda alemã de meninos que dominou a Europa com rock de arena e letras melodramáticas.",
        "twenty one pilots" to "A dupla de Columbus que mistura hip-hop, rock e eletrônica em um show que cabe em qualquer estádio.",
        "u2" to "U2 começou em Dublin em 1979 e ainda hoje transforma arena em igreja de rock.",
        "ultje" to "Banda alternativa dos anos 90 com pegada independente — cult entre quem coleciona vinis raros.",
        "marshmello" to "O DJ de capacete que ninguém sabe quem é — parte do charme é nunca mostrar o rosto.",
        "ariana grande" to "Ariana nasceu em Flórida e virou uma das maiores vozes do pop com longas notas de quatro oitavas.",
        "billie eilish" to "Billie e seu irmão Finneas gravaram o sucesso de 2019 no quarto da casa deles em Los Angeles, sem estúdio.",
        "drake" to "Drake é de Toronto e transformou a cidade canadense em capital do R&B moderno.",
        "post malone" to "Post Malone mistura rap, pop e um pouco de grunge — o visual é o que mais engana.",
        "queens of the stone age" to "QOTSA nasceu da poeira do Kyuss e construiu um rock pesado com groove futurista nos anos 2000.",
        "radiohead" to "Radiohead lançou um dos discos mais influentes do rock de todos os tempos, gravado de propósito com calma e ruído.",
        "rihanna" to "Rihanna saiu de Barbados para virar potência mundial de Barbados ao mundo — e ainda virou magnata.",
        "beyonce" to "Beyoncé escreve em forma de visuais e quebrou recordes ao colocar os próprios shows na linha do tempo.",
        "bruno mars" to "Bruno Mars é havaiano e vive de funk, soul e pop — cada show é uma máquina de músicas de dança.",
        "justin bieber" to "Justin Bieber foi descoberto num vídeo caseiro no YouTube e virou figura obrigatória do pop mundial.",
        "kanye west" to "Kanye West empurra o hip-hop para o futuro a cada disco, de Soul Sample a IMAX.",
        "jorge e matheus" to "A dupla de Goiânia que virou a locomotiva do sertanejo universitário nos anos 2010.",
        "gusttavo lima" to "Gusttavo é de Patos de Minas e plantou na música o estilo que lota estádios do Brasil inteiro.",
        "marília mendonça" to "Marília Mendonça compôs do chão de Goiânia o estilo sofrência que tocou o Brasil na década passada.",
        "zézé di camargo e luciano" to "Zezé e Luciano são irmãos de Pirenópolis e estão na estrada desde os anos 90, sem parar de lançar hits.",
        "luan santana" to "Luan encantou o Brasil com balanço e viola ainda adolescente, direto de Campo Grande.",
        "wesley safadão" to "Safadão saiu do forró de Fortaleza para um dos maiores circuitos de shows do país.",
        "fernando e sorocaba" to "Fernando e Sorocaba modernizaram o sertanejo com sustentação pop nos palcos.",
        "luis fonsi" to "Luis Fonsi é porto-riquenho e levou o reggaeton latino a todos os continentes.",
        "daddy yankee" to "Daddy Yankee é o 'Rei do Reggaeton' e ajudou Porto Rico a mostrar seu território sonoro para o mundo.",
        "bad bunny" to "Bad Bunny é porto-riquenho e virou o artista latino mais ouvido do planeta com reggaeton e experimentos.",
        "anitta" to "Anitta começa nas favelas do Rio e hoje gira o mundo com funk, pop e fantasia.",
        "mc kevinho" to "Kevinho ajudou a popularizar o passinho brasileiro com concorrências de funk de verão.",
        "mc hariel" to "Hariel nasceu no funk de São Paulo e chegou ao topo com batidas que o Brasil inteiro cantou.",
        "gera bzeira" to "Gera Bzeira é o pout-pourri do forró — vai da viola ao funk de raiz num só show.",
        "xand avião" to "Xand Avião sabe cantar de tudo um pouco: do forró de verdade ao arrocha que só pode ser a caminho do aeroporto.",
        "márcia fellipe" to "Márcia Fellipe trouxe voz própria para o forró feminino e abriu espaço na cena dominada por homens.",
        "jean e rafael" to "A dupla de Goiás que nasceu do sertanejo universitário e virou presença garantida nas festas do interior."
    )

    /** O mesmo mapa, com a chave normalizada (sem acento) para casar com o nome do artista. */
    private val FACTS_BY_KEY: Map<String, String> =
        FACTS.entries.associate { normalize(it.key) to it.value }
}
