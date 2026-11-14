package com.pulsa.player.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * As strings novas precisam existir em todas as traduções.
 *
 * O aviso de servidor em claro (`server_http_warning`) é o texto que explica que o token e
 * a biblioteca passam sem criptografia. Se ele faltar num idioma, o `getString` devolve o
 * id da resource — e o usuário lê "server_http_warning" no lugar da explicação.
 *
 * A regra das traduções mora nos XML de `res/values*`, então o teste lê os XML.
 */
class StringsTraduzidasTest {

    private val tradicoes = listOf("values", "values-pt-rPT", "values-en", "values-es")

    @Test
    fun oAvisoDeServidorEmClaroExisteEmTodasAsTraducoes() {
        for (locale in tradicoes) {
            val xml = leStrings(locale)
            assertTrue(
                "faltou a string server_http_warning em $locale",
                xml.contains("name=\"server_http_warning\"")
            )
        }
    }

    @Test
    fun oAvisoDeServidorEmClaroFalaDaCriptografia() {
        val xml = leStrings("values")
        val linha = xml.lines().firstOrNull { it.contains("name=\"server_http_warning\"") }
        assertTrue("a string server_http_warning não encontrada", linha != null)
        assertTrue(
            "o aviso precisa falar em claro/criptografia: $linha",
            linha!!.contains("claro") || linha.contains("criptograf")
        )
        assertTrue(
            "o aviso precisa sugerir https: $linha",
            linha.contains("https://")
        )
    }

    @Test
    fun nenhumaTraducaoFicaOrfa() {
        // Os locales são *overlay*: só trazem as chaves que diferem do pt-BR. A regra é
        // unilateral — toda chave traduzida precisa existir na base, senão a tradução é
        // morta (e o `getString` do id nunca aparece).
        val base = chaves(leStrings("values"))
        for (locale in tradicoes.drop(1)) {
            val outras = chaves(leStrings(locale))
            val orfas = outras - base
            assertTrue(
                "$locale tem ${orfas.size} chave(s) que não existem no pt-BR: ${orfas.take(5)}",
                orfas.isEmpty()
            )
        }
    }

    private fun chaves(xml: String): Set<String> =
        Regex("name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toSet()

    private fun leStrings(locale: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (relativo in listOf(
                "app/src/main/res/$locale/strings.xml",
                "src/main/res/$locale/strings.xml"
            )) {
                val arquivo = File(dir, relativo)
                if (arquivo.exists()) return arquivo.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("não achei res/$locale/strings.xml a partir de ${File("").absolutePath}")
    }
}
