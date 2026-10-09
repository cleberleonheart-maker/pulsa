package com.pulsa.player.podcast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.ParserConfigurationException

/**
 * F3 — cobertura do [FeedParser].
 *
 * Os casos aqui não são por cobertura, e sim pelos jeitos de feed que quebram de verdade:
 * namespace declarado, `itunes:duration` em três formatos, item sem `guid`, HTML na
 * descrição e feed de vídeo. Um parser de feed que só foi testado com o feed "bonito" da
 * documentação funciona nas mãos de ninguém.
 */
class FeedParserTest {

    private fun xml(body: String): ByteArray = body.trimIndent().toByteArray()

    // ---- RSS ------------------------------------------------------------------------

    @Test
    fun `le RSS 2_0 com enclosure`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>Diário Semanal</title>
                    <link>https://exemplo.com/podcast</link>
                    <description>Um podcast qualquer</description>
                    <itunes:author>Ana</itunes:author>
                    <itunes:image href="https://exemplo.com/capa.jpg"/>
                    <item>
                      <title>Episódio 1</title>
                      <guid>ep-1</guid>
                      <pubDate>Tue, 05 Oct 2026 09:00:00 GMT</pubDate>
                      <itunes:duration>1:02:03</itunes:duration>
                      <enclosure url="https://exemplo.com/ep1.mp3" type="audio/mpeg" length="1"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )

        assertNotNull(p)
        p!!
        assertEquals("Diário Semanal", p.title)
        assertEquals("https://exemplo.com/podcast", p.feedUrl)
        assertEquals("Ana", p.author)
        assertEquals("https://exemplo.com/capa.jpg", p.artworkUrl)
        assertEquals(1, p.episodes.size)

        val ep = p.episodes.first()
        assertEquals("ep-1", ep.guid)
        assertEquals("Episódio 1", ep.title)
        assertEquals("https://exemplo.com/ep1.mp3", ep.audioUrl)
        assertFalse(ep.isVideo)
        // 1h02m03s em segundos: o dobre é o detalhe, feed de podcast não usa só `M:SS`.
        assertEquals(3723L, ep.durationSec)
    }

    /**
     * O `itunes:duration` com prefixo é o caso mais comum do mundo e o que mais quebra parser
     * escrito com `getElementsByTagName`: o elemento chama `itunes:duration`, não `duration`.
     */
    @Test
    fun `acha duracao com namespace itunes declarado`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
                  <channel>
                    <title>P</title><link>https://e.com</link>
                    <item>
                      <title>E</title><guid>g</guid>
                      <itunes:duration>45:30</itunes:duration>
                      <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )
        assertEquals(2730L, p!!.episodes.first().durationSec)
    }

    @Test
    fun `aceita duracao em segundos crus`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0">
                  <channel>
                    <title>P</title><link>https://e.com</link>
                    <item><title>E</title><guid>g</guid><duration>1800</duration>
                      <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                  </channel>
                </rss>
                """
            )
        )
        assertEquals(1800L, p!!.episodes.first().durationSec)
    }

    @Test
    fun `feed sem duracao fica com zero em vez de quebrar`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>E</title><guid>g</guid>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        assertEquals(0L, p!!.episodes.first().durationSec)
    }

    // ---- item sem guid --------------------------------------------------------------

    /**
     * Feed sem `guid` é feed ruim, mas existe. O que não pode é o item ser descartado: o
     * usuário assina, não vê episódio nenhum, e não entende o motivo.
     */
    @Test
    fun `item sem guid usa a url do enclosure como identidade`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>Sem guid</title>
                    <enclosure url="https://e.com/sem-guid.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        val ep = p!!.episodes.single()
        assertEquals("https://e.com/sem-guid.mp3", ep.guid)
        assertEquals("Sem guid", ep.title)
    }

    /** Item sem enclosure não tem áudio: some da lista em vez de aparecer algo que não toca. */
    @Test
    fun `item sem enclosure e descartado`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>Só texto</title><guid>g</guid></item>
                  <item><title>Com audio</title><guid>g2</guid>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        assertEquals(1, p!!.episodes.size)
        assertEquals("Com audio", p.episodes.first().title)
    }

    /** Feed que repete o item mais recente não pode virar episódio duplicado. */
    @Test
    fun `dedup por guid dentro do proprio feed`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>A</title><guid>mesmo</guid>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                  <item><title>A repetido</title><guid>mesmo</guid>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        assertEquals(1, p!!.episodes.size)
    }

    // ---- Atom ----------------------------------------------------------------------

    @Test
    fun `le Atom com link e entry`() {
        val p = FeedParser.parse(
            xml(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>Atom Cast</title>
                  <subtitle>Resumo</subtitle>
                  <link href="https://atom.com/podcast" rel="alternate"/>
                  <link href="https://atom.com/feed.xml" rel="self"/>
                  <entry>
                    <title>Entrada 1</title>
                    <id>tag:atom.com,2026:1</id>
                    <updated>2026-10-05T09:00:00.000Z</updated>
                    <summary>Resumo do episódio</summary>
                    <link rel="enclosure" href="https://atom.com/e1.mp3" type="audio/mpeg"/>
                  </entry>
                </feed>
                """
            )
        )

        assertNotNull(p)
        assertEquals("Atom Cast", p!!.title)
        // `rel="self"` é o XML, não o endereço do podcast: usar ele faria o app guardar um
        // `.xml` e depois tentar tocar isso como áudio.
        assertEquals("https://atom.com/podcast", p.feedUrl)
        val ep = p.episodes.single()
        assertEquals("https://atom.com/e1.mp3", ep.audioUrl)
        assertEquals("tag:atom.com,2026:1", ep.guid)
        assertEquals("Resumo do episódio", ep.description)
    }

    // ---- RDF / RSS 1.0 --------------------------------------------------------------

    /** RSS 1.0 usa `<channel>` como irmão de `<item>`, sem `<rss>` — daí o quarto caminho. */
    @Test
    fun `le RDF com channel como irmao de item`() {
        val p = FeedParser.parse(
            xml(
                """
                <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                         xmlns="http://purl.org/rss/1.0/"
                         xmlns:dc="http://purl.org/dc/elements/1.1/">
                  <channel rdf:about="https://rdf.com">
                    <title>RDF Cast</title>
                    <link>https://rdf.com/podcast</link>
                  </channel>
                  <item rdf:about="https://rdf.com/1">
                    <title>RDF 1</title>
                    <guid>rdf-1</guid>
                    <dc:creator>Bia</dc:creator>
                    <enclosure url="https://rdf.com/a.mp3" type="audio/mpeg"/>
                  </item>
                </rdf:RDF>
                """
            )
        )
        assertNotNull(p)
        assertEquals("RDF Cast", p!!.title)
        assertEquals(1, p.episodes.size)
    }

    // ---- data -------------------------------------------------------------------------

    @Test
    fun `data RFC 822 do RSS`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>E</title><guid>g</guid>
                    <pubDate>Tue, 05 Oct 2026 09:00:00 GMT</pubDate>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        val ep = p!!.episodes.single()
        assertTrue("deve reconhecer data RFC 822", ep.publishedAt > 0L)
    }

    @Test
    fun `data ISO do Atom com milissegundos`() {
        val p = FeedParser.parse(
            xml(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>P</title><link href="https://e.com"/>
                  <entry><title>E</title><id>g</id>
                    <updated>2026-10-05T09:00:00.000Z</updated>
                    <link rel="enclosure" href="https://e.com/a.mp3"/>
                  </entry>
                </feed>
                """
            )
        )
        assertTrue(p!!.episodes.single().publishedAt > 0L)
    }

    /** Data ilegível não pode virar data de hoje: a lista inteira viraria "novo". */
    @Test
    fun `data invalida vira zero e nao hoje`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>E</title><guid>g</guid>
                    <pubDate>ontem de tarde</pubDate>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        assertEquals(0L, p!!.episodes.single().publishedAt)
    }

    @Test
    fun `ordena do mais novo para o mais antigo`() {
        val p = FeedParser.parse(
            xml(
                """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <title>P</title><link href="https://e.com"/>
                  <entry><title>Antigo</title><id>a</id>
                    <updated>2020-01-01T00:00:00.000Z</updated>
                    <link rel="enclosure" href="https://e.com/a.mp3"/></entry>
                  <entry><title>Recente</title><id>b</id>
                    <updated>2026-01-01T00:00:00.000Z</updated>
                    <link rel="enclosure" href="https://e.com/b.mp3"/></entry>
                </feed>
                """
            )
        )
        assertEquals(listOf("Recente", "Antigo"), p!!.episodes.map { it.title })
    }

    // ---- vídeo ------------------------------------------------------------------------

    /**
     * Feed de vídeo é o formato comum hoje, e o `type` do enclosure é o que decide. Sem
     * separar, o episódio entraria na fila como áudio e o player tocaria um vídeo sem imagem.
     */
    @Test
    fun `enclosure de video e marcado como video`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>Video Pod</title><link>https://e.com</link>
                  <item><title>V1</title><guid>v1</guid>
                    <enclosure url="https://e.com/v1.mp4" type="video/mp4" length="1"/></item>
                </channel></rss>
                """
            )
        )
        val ep = p!!.episodes.single()
        assertTrue(ep.isVideo)
        assertEquals("https://e.com/v1.mp4", ep.videoPageUrl)
    }

    /** `video/mp4; codecs=...` é como o feed manda; o `startsWith` cobre, `contains` também. */
    @Test
    fun `type de video com parametros ainda conta como video`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>V</title><link>https://e.com</link>
                  <item><title>V1</title><guid>v</guid>
                    <enclosure url="https://e.com/v.mp4" type="video/mp4; codecs=avc1"/></item>
                </channel></rss>
                """
            )
        )
        assertTrue(p!!.episodes.single().isVideo)
    }

    /** `yt:videoId` sem enclosure: podcast em vídeo que publica só o link. */
    @Test
    fun `yt videoId vira url de pagina`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0" xmlns:yt="http://www.youtube.com/xml/schemas/2015">
                  <channel>
                    <title>YT Pod</title><link>https://e.com</link>
                    <item><title>Vídeo 1</title><guid>y1</guid>
                      <yt:videoId>abc123XYZ_0</yt:videoId></item>
                  </channel>
                </rss>
                """
            )
        )
        val ep = p!!.episodes.single()
        assertTrue(ep.isVideo)
        assertTrue(
            "yt:videoId deve virar URL de página",
            ep.videoPageUrl.contains("abc123XYZ_0")
        )
    }

    // ---- descrição com HTML ----------------------------------------------------------

    /**
     * Descrição de podcast é quase sempre um parágrafo de HTML. Deixar a tag vazar para a
     * tela mostra `<p>` e `&nbsp;` literalmente na lista.
     */
    @Test
    fun `descricao com html fica em texto`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><title>E</title><guid>g</guid>
                    <description>&lt;p&gt;Olá &amp;amp; bem-vindo&lt;/p&gt;</description>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        val d = p!!.episodes.single().description
        assertFalse("não deve sobrar tag", d.contains("<"))
        assertFalse("não deve sobrar &amp;", d.contains("&amp;"))
        assertTrue(d.contains("bem-vindo"))
    }

    @Test
    fun `stripHtml resolve entidade numerica`() {
        assertEquals("a & b", FeedParser.stripHtml("a &amp; b"))
        assertEquals("A", FeedParser.stripHtml("&#65;"))
        assertEquals("A", FeedParser.stripHtml("&#x41;"))
    }

    // ---- entradas invalidas -------------------------------------------------------------

    @Test
    fun `xml malformado devolve null sem estourar`() {
        assertNull(FeedParser.parse(xml("<rss><channel><title>Sem fechar")))
    }

    @Test
    fun `vazio devolve null`() {
        assertNull(FeedParser.parse(xml("")))
    }

    /** Feed sem título é feed de outra coisa; sem título não há o que mostrar na lista. */
    @Test
    fun `sem titulo devolve null`() {
        assertNull(
            FeedParser.parse(
                xml("<rss><channel><link>https://e.com</link></channel></rss>")
            )
        )
    }

    /** Item sem título entra como "Sem título", e não some da lista. */
    @Test
    fun `item sem titulo recebe placeholder`() {
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0"><channel>
                  <title>P</title><link>https://e.com</link>
                  <item><guid>g</guid>
                    <enclosure url="https://e.com/a.mp3" type="audio/mpeg"/></item>
                </channel></rss>
                """
            )
        )
        assertEquals("Sem título", p!!.episodes.single().title)
    }

    // ---- modelo -----------------------------------------------------------------------

    @Test
    fun `finished marca ouvido no fim mas nao no comeco`() {
        val duracao = 3600L
        val noComeco = Episode(guid = "g", title = "t", audioUrl = "u", durationSec = duracao)
        assertFalse(noComeco.finished)

        val quaseFim = noComeco.copy(positionMs = duracao * 950)
        assertTrue(quaseFim.finished)

        // Ouvido à mão vale sempre, mesmo sem tempo.
        assertTrue(noComeco.copy(played = true).finished)
    }

    @Test
    fun `playableUrl prefere o arquivo local`() {
        val ep = Episode(guid = "g", title = "t", audioUrl = "https://e.com/a.mp3")
        assertEquals("https://e.com/a.mp3", ep.playableUrl)
        assertFalse(ep.downloaded)

        // Baixado: é o arquivo que faz o episódio tocar sem internet.
        val baixado = ep.copy(filePath = "/data/a.mp3")
        assertEquals("/data/a.mp3", baixado.playableUrl)
        assertTrue(baixado.downloaded)
    }

    // ---- feed que o app precisa explicar --------------------------------------------

    @Test
    fun `page html nao passa por feed`() {
        // O caso que produce "feed inválido" com mais frequência: a pessoa cola o link do
        // site do programa (que responde 200), e não o do feed. A mensagem precisa dizer
        // isso — "inválido" faz a pessoa tentar de novo com o mesmo endereço.
        val html = "<!DOCTYPE html><html><head><title>Meu Podcast</title></head>" +
            "<body><a href='/feed.xml'>RSS</a></body></html>"
        val r = FeedParser.parseOrError(xml(html))
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("página"))
        assertNull(FeedParser.parse(xml(html)))
    }

    @Test
    fun `ampersand solto na descricao ainda parseia`() {
        // "&" sem entidade é o defeito de feed mais comum que existe: o DocumentBuilder é
        // estrito e estoura, e o usuário vê apenas "feed inválido" num podcast perfectly
        // bom. O texto tem de sobreviver inteiro, com o "&" no lugar.
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0">
                  <channel>
                    <title>AT&T Talks</title>
                    <link>https://exemplo.com/podcast</link>
                    <description>Rock&Roll e Juan & Maria</description>
                    <item>
                      <title>EP 1</title>
                      <guid>ep-1</guid>
                      <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )
        assertNotNull(p)
        assertEquals("AT&T Talks", p!!.title)
        assertEquals("Rock&Roll e Juan & Maria", p.description)
        assertEquals(1, p.episodes.size)
    }

    @Test
    fun `entidade valida continua valida`() {
        // A Soldier usa `&amp;` e `&#39;`: o reparo do "&" não pode transformar a entidade
        // em `&amp;amp;` e mostrar "AT&amp;T" na tela do podcast.
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0">
                  <channel>
                    <title>Rock &amp; Roll</title>
                    <link>https://exemplo.com/podcast</link>
                    <description>dono &#39; do jogo</description>
                    <item>
                      <title>EP 1</title>
                      <guid>ep-1</guid>
                      <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )
        assertNotNull(p)
        assertEquals("Rock & Roll", p!!.title)
        assertEquals("dono ' do jogo", p.description)
    }

    @Test
    fun `BOM nao derruba o parse`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            """
            <rss version="2.0"><channel><title>Com BOM</title>
            <link>https://exemplo.com/podcast</link>
            <item><title>EP 1</title><guid>ep-1</guid>
            <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
            </item></channel></rss>
            """.trimIndent().toByteArray()
        assertEquals("Com BOM", FeedParser.parse(bom)?.title)
    }

    @Test
    fun `xml que nao presta diz o que houve`() {
        val r = FeedParser.parseOrError(xml("<rss><channel><title>Quebrado"))
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message.orEmpty()
        assertTrue("mensagem deveria citar o XML: $msg", msg.contains("XML"))
    }

    @Test
    fun `rss que nao e de podcast diz que falta titulo`() {
        // RSS de notícias responde 200 e parseia: o que não existe é título/link de podcast.
        val r = FeedParser.parseOrError(
            xml("<rss version=\"2.0\"><channel><item><title>Notícia</title></item></channel></rss>")
        )
        assertTrue(r.isFailure)
        assertNull(FeedParser.parse(xml("<rss version=\"2.0\"><channel><item><title>N</title></item></channel></rss>")))
    }

    @Test
    fun `feed so com atom link self`() {
        // Formato do Megaphone, uma das maiores plataformas de podcast: RSS com
        // `<atom:link rel="self">` e **nenhum** `<link>` de texto. A regra antiga aceitava só
        // `rel` vazio ou "alternate", então o feed era recusado como inválido — com título e
        // episódios bons na resposta. O `self` ainda é a melhor identidade: é o mesmo
        // endereço que a pessoa colou, que é o que a assinatura deduplica.
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
                  <channel>
                    <atom:link href="https://feeds.megaphone.fm/ABC" rel="self"
                               type="application/rss+xml"/>
                    <title>Crash Dummies</title>
                    <description>Comédia</description>
                    <item>
                      <title>EP 1</title>
                      <guid>ep-1</guid>
                      <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )
        assertNotNull(p)
        assertEquals("https://feeds.megaphone.fm/ABC", p!!.feedUrl)
        assertEquals("Crash Dummies", p.title)
        assertEquals(1, p.episodes.size)
    }

    @Test
    fun `link de texto do RSS ganha do self`() {
        // Os dois convivem em alguns feeds, e o link do site é o que o app quer mostrar: o
        // `self` é o endereço do XML.
        val p = FeedParser.parse(
            xml(
                """
                <rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">
                  <channel>
                    <atom:link href="https://cdn.exemplo/feed.xml" rel="self"/>
                    <title>Do Site</title>
                    <link>https://exemplo.com/podcast</link>
                    <item>
                      <title>EP 1</title>
                      <guid>ep-1</guid>
                      <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
                    </item>
                  </channel>
                </rss>
                """
            )
        )
        assertEquals("https://exemplo.com/podcast", p!!.feedUrl)
    }

    @Test
    fun `corpo gzippado ainda parseia`() {
        // Há servidor que manda gzip mesmo sem o cliente pedir Accept-Encoding, e o
        // HttpURLConnection só descompacta quando quem pede é a gente. Aí o parser lia bytes
        // binários e devolvia "XML malformado" para um feed perfeitamente bom.
        val bruto = xml(
            """
            <rss version="2.0">
              <channel>
                <title>Comprimido</title>
                <link>https://exemplo.com/podcast</link>
                <item>
                  <title>EP 1</title>
                  <guid>ep-1</guid>
                  <enclosure url="https://exemplo.com/1.mp3" type="audio/mpeg" length="1"/>
                </item>
              </channel>
            </rss>
            """
        )
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(out).use { it.write(bruto) }
        val p = FeedParser.parseOrError(out.toByteArray())
        assertTrue(p.isSuccess)
        assertEquals("Comprimido", p.getOrThrow().title)
        assertEquals(1, p.getOrThrow().episodes.size)
    }

    @Test
    fun `resposta json e identificada`() {
        // Colar o link da busca do iTunes em vez do do feed: a resposta é JSON, e "XML
        // malformado" não diz o que a pessoa fez.
        val json = "{\"resultCount\":0,\"results\":[]}".toByteArray(Charsets.UTF_8)
        val r = FeedParser.parseOrError(json)
        assertTrue(r.isFailure)
        assertTrue(r.exceptionOrNull()!!.message!!.contains("JSON"))
    }

    @Test
    fun `xml malformado mostra a causa real`() {
        // A regressão: o `read` engolia a exceção, devolvia null, e o caminho de erro via
        // exceptionOrNull() == null -> "erro desconhecido". A pessoa via "XML malformado:
        // erro desconhecido", que é a frase mais inútil que o app podia imprimir.
        val r = FeedParser.parseOrError(xml("<rss><channel><title>Quebrado"))
        assertTrue(r.isFailure)
        val msg = r.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("XML malformado"))
        assertFalse(msg.contains("erro desconhecido"))
    }

    @Test
    fun `feature que o parser do Android nao conhece nao derruba a leitura`() {
        // No Android o `DocumentBuilderFactory` não conhece `disallow-doctype-decl` e o
        // `setFeature` **lança** com a URL no texto do erro — que era o que virava
        // "XML malformado: http://apache.org/xml/features/disallow-doctype-decl" ao assinar um
        // podcast. O teste no JVM não pegava porque o Xerces conhece a feature. Aqui o factory
        // imita o Android: `configure` tem de ignorar a feature desconhecida e ainda assim
        // entregar um parser que funciona.
        val real = DocumentBuilderFactory.newInstance()
        val android = object : DocumentBuilderFactory() {
            override fun newDocumentBuilder() = real.newDocumentBuilder()
            override fun setAttribute(name: String?, value: Any?) { real.setAttribute(name, value) }
            override fun getAttribute(name: String?): Any? = real.getAttribute(name)
            override fun setFeature(name: String?, value: Boolean) {
                if (name == "http://apache.org/xml/features/disallow-doctype-decl") {
                    throw ParserConfigurationException(name)
                }
                real.setFeature(name, value)
            }
            override fun getFeature(name: String?): Boolean = real.getFeature(name)
            // No Android este método **sempre** lança, mesmo com `false`.
            override fun setXIncludeAware(state: Boolean) {
                throw UnsupportedOperationException(
                    "This parser does not support specification \"Unknown\" version \"0.0\""
                )
            }
        }
        FeedParser.configure(android)
        val doc = android.newDocumentBuilder()
            .parse("<rss><channel><title>Ok</title></channel></rss>".byteInputStream())
        assertEquals("channel", doc.documentElement.firstChild.nodeName)
    }
}
