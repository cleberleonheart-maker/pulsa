package com.pulsa.player.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A assinatura do release não pode ter *fallback*.
 *
 * O que estava commitado era a chave de **debug** do Android (`androiddebugkey`, senha
 * `android`, CN=Android Debug) e ela assinava o APK de release. Essa chave é pública:
 * com ela qualquer pessoa gera um APK com o mesmo `applicationId` e a mesma assinatura,
 * e o Android aceita como atualização do Pulsa — a própria checagem de assinatura do app
 * (`util/UpdateChecker`) passaria. Um APK falsificado com o nome do Pulsa é a pior coisa
 * que pode acontecer com este app.
 *
 * A defesa é o build: sem `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (e sem
 * `KEYSTORE_PATH`), o `assembleRelease` **falha**. Melhor não publicar do que publicar um
 * APK que qualquer um pode falsificar.
 *
 * A regra mora em `build.gradle.kts` e no workflow — fora do alcance dos testes de código —
 * então o teste lê os dois arquivos.
 */
class AssinaturaReleaseTest {

    @Test
    fun oReleaseNaoTemSenhaDeFallback() {
        val bloco = bloco("app/build.gradle.kts", "create(\"pulsaRelease\")")
        assertTrue(
            "o signingConfig de release nao pode ter senha de fallback:\n$bloco",
            !bloco.contains("\"android\"")
        )
        assertTrue(
            "o signingConfig de release nao pode usar o alias de debug:\n$bloco",
            !bloco.contains("androiddebugkey")
        )
    }

    @Test
    fun oReleaseLeAsSenhasDoAmbiente() {
        val bloco = bloco("app/build.gradle.kts", "create(\"pulsaRelease\")")
        for (variavel in listOf("KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")) {
            assertTrue(
                "o signingConfig de release deveria ler $variavel do ambiente:\n$bloco",
                bloco.contains(variavel)
            )
        }
    }

    @Test
    fun oBuildTypeDeReleaseUsaAConfigDeRelease() {
        val gradle = leArquivo("app/build.gradle.kts")
        val release = gradle.substringAfter("release {")
        assertTrue(
            "o buildType de release deveria usar o signingConfig pulsaRelease:\n$release",
            release.contains("pulsaRelease")
        )
    }

    @Test
    fun oWorkflowNaoTemSegredoDeFallback() {
        // Os comentários do workflow explicam o porquê do fallback ("android"/"androiddebugkey"),
        // então a busca é no que o YAML **executa** — as linhas sem comentário.
        val yaml = leArquivo(".github/workflows/build.yml")
            .lines()
            .filterNot { it.trimStart().startsWith("#") }
            .joinToString("\n")
        assertTrue(
            "o workflow nao pode ter fallback de senha:\n$yaml",
            !yaml.contains(":-android") && !yaml.contains("androiddebugkey")
        )
    }

    @Test
    fun oKeystoreNaoEstaNoGitIgnore() {
        val ignore = leArquivo(".gitignore")
        assertTrue(".gitignore deveria ignorar *.keystore", ignore.contains("*.keystore"))
        assertTrue(".gitignore deveria ignorar *.jks", ignore.contains("*.jks"))
    }

    /** O texto do trecho que começa em `marcador` e vai até o próximo `create(`. */
    private fun bloco(caminho: String, marcador: String): String {
        val texto = leArquivo(caminho)
        val inicio = texto.indexOf(marcador)
        assertTrue("não achei $marcador em $caminho", inicio >= 0)
        val fim = texto.indexOf("create(", inicio + marcador.length)
        return if (fim > 0) texto.substring(inicio, fim) else texto.substring(inicio)
    }

    private fun leArquivo(relativo: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val arquivo = File(dir, relativo)
            if (arquivo.exists()) return arquivo.readText()
            dir = dir.parentFile
        }
        throw AssertionError("não achei $relativo a partir de ${File("").absolutePath}")
    }
}
