package com.pulsa.player.util

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

object DjFacts {

    fun normalize(name: String?): String {
        if (name.isNullOrBlank()) return ""
        return name.trim().lowercase(Locale.ROOT)
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
    }

    fun curiosityFor(artistName: String): String? {
        val key = normalize(artistName)
        if (key.isEmpty()) return null
        return FACTS[key] ?: FACTS.entries.firstOrNull { entry ->
            if (entry.key.isEmpty()) false else entry.key in key
        }?.value
    }

    fun hasCuriosity(artistName: String): Boolean = curiosityFor(artistName) != null

    private const val REMOTE_COOLDOWN_MS = 1100L
    private const val CURIOSITY_MIN_MS = 5L * 60_000L
    private const val CURIOSITY_MAX_MS = 10L * 60_000L

    private val remoteCache = HashMap<String, String>()
    private val remoteInFlight = HashSet<String>()
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
        synchronized(remoteCache) {
            remoteCache[key]?.let { cached ->
                onResult(cached.ifBlank { null })
                return
            }
            if (key in remoteInFlight) {
                onResult(null)
                return
            }
            remoteInFlight.add(key)
        }
        ThreadPool.post {
            val fact = musicBrainzFact(key)
            synchronized(remoteCache) {
                remoteCache[key] = fact ?: ""
                remoteInFlight.remove(key)
            }
            ThreadPool.onUi { onResult(fact) }
        }
    }

    private fun musicBrainzFact(key: String): String? {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRemoteQueryMs < REMOTE_COOLDOWN_MS) return null
        lastRemoteQueryMs = now
        val query = URLEncoder.encode("artist:\"$key\"", "UTF-8")
        val url = "https://musicbrainz.org/ws/2/artist/?query=$query&limit=3&fmt=json"
        val json = httpGetJson(url) ?: return null
        val artists = runCatching { json.getJSONArray("artists") }.getOrNull() ?: return null
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
        val a = best ?: return null
        val name = runCatching { a.getString("name") }.getOrNull() ?: return null
        val type = runCatching { a.getString("type") }.getOrNull()
        val area = runCatching { a.getJSONObject("area").getString("name") }.getOrNull()
            ?: runCatching { a.getString("country") }.getOrNull()
        val life = runCatching { a.getJSONObject("life-span").getString("begin") }.getOrNull()
        val year = life?.take(4)?.toIntOrNull()
        val kind = when (type) {
            "Person" -> "artista solo"
            "Group" -> "banda"
            else -> null
        }
        return when {
            area != null && year != null ->
                "Segundo o MusicBrainz, $name surgiu em $area e está na ativa desde $year."
            area != null ->
                "Segundo o MusicBrainz, $name tem raízes registradas em $area."
            year != null ->
                "Segundo o MusicBrainz, $name está em atividade desde $year."
            kind != null ->
                "Segundo o MusicBrainz, $name é $kind."
            else -> null
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

    fun fallbackFor(songYear: Int, hasMB: Boolean, isCover: Boolean, isLive: Boolean, albumName: String?): String {
        val mb = hasMB
        return when {
            isCover && isLive -> "Você tem uma versão ao vivo dessa faixa — versões assim sempre têm uma energia diferente, percebe?"
            isCover -> "Essa é uma versão/cover — o artista que você ouve agora não é o dono original da faixa."
            isLive && mb -> "Essa é uma gravação ao vivo com faixa de apoio. A edição mistura a voz ao vivo com um playback gravado."
            isLive -> "Esse registro é ao vivo — dá pra sentir o clima da plateia até no fone!"
            songYear in 1900..1990 -> "Essa faixa é de $songYear — som das eras do vinil, gravado num mundo bem diferente do de hoje."
            songYear in 1990..2009 -> "Essa música é de $songYear, do auge do CD e do começo do streaming."
            songYear >= 2009 -> "Essa é dos anos 2000 em diante — lançada em $songYear, quando o mundo já tocava pelo celular."
            albumName != null && songYear > 1900 -> "Essa faixa entrou na sua biblioteca vinda de $albumName, um lançamento de $songYear."
            else -> "Sua biblioteca guarda cada detalhe dessa faixa — eu só estou preparando o clima."
        }
    }

    private fun fallbackOrder(
        song: com.pulsa.player.model.Song?,
        albumName: String?
    ): String {
        val year = song?.year ?: 0
        return when {
            year in 1900..1990 -> "De $year! Essa faixa tem a cara das primeiras playlists da história, feitas em fitas cassete."
            year in 1990..2009 -> "Ano $year. Foi nessa época que o MP3 transformou a sua coleção de discos em arquivos."
            year >= 2010 -> "De $year, uma das mais novas da sua biblioteca."
            song != null -> "Conhece a história dessa faixa? Eu sempre gosto de mexer nos detalhes."
            else -> "O algoritmo me contou segredos sobre a sua biblioteca que nem você sabe."
        }
    }

    fun leadIn(intensity: String): String = when (intensity) {
        Settings.DJ_CALM -> "Um momento, antes da próxima:"
        Settings.DJ_WILD -> "Se liga nessa:"
        else -> "Antes de seguir, uma curiosidade:"
    }

    fun curiosityText(song: com.pulsa.player.model.Song?, artistName: String, albumName: String?, intensity: String): String {
        val fact = curiosityFor(artistName)
        val body = fact ?: fallbackOrder(song, albumName)
        return if (fact != null) "${leadIn(intensity)} Você sabia? $body" else body
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
}