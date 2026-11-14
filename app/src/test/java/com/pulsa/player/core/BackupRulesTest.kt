package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O que o backup do Google pode levar.
 *
 * Dois arquivos de `SharedPreferences` não podem sair do aparelho:
 *
 * - `pulsa_secrets.xml` — chave do Gemini, do Last.fm, do Podcast Index, token de admin, o
 *   da nuvem, o token de reconhecimento e o PIN de children. São cifrados com a MasterKey do
 *   Keystore, e a MasterKey **não** viaja: no aparelho novo a leitura falha e o app entra em
 *   reset;
 * - `pulsa_account.xml` — a conta (já estava fora desde antes).
 *
 * O `pulsa_settings.xml` **viaja**: é onde estão as preferências da pessoa (idioma, EQ,
 * crossfade, acentuação, teto de download). Excluir o arquivo inteiro apagava tudo isso no
 * aparelho novo; o que era credencial dentro dele foi para as prefs cifradas.
 *
 * A regra mora em XML (`res/xml`), não em código — então o teste lê o XML. É o único jeito
 * de a exclusão não virar "achismo" depois de uma edição de resource.
 */
class BackupRulesTest {

    private val sensiveis = listOf("pulsa_account.xml", "pulsa_secrets.xml")

    @Test
    fun backupRulesExcluemOsArquivosSensiveis() {
        val xml = leXml("backup_rules.xml")
        for (nome in sensiveis) {
            assertTrue(
                "backup_rules.xml deveria excluir $nome:\n$xml",
                xml.contains("path=\"$nome\"")
            )
        }
    }

    @Test
    fun dataExtractionRulesExcluemOsArquivosSensiveis() {
        val xml = leXml("data_extraction_rules.xml")
        for (nome in sensiveis) {
            assertTrue(
                "data_extraction_rules.xml deveria excluir $nome:\n$xml",
                xml.contains("path=\"$nome\"")
            )
        }
    }

    @Test
    fun cloudBackupETransferenciaExcluemOsMesmos() {
        // O "device-transfer" (o botão "transferir para outro aparelho") não pode vazar o
        // que o cloud backup não leva: são dois caminhos para o mesmo arquivo.
        val xml = leXml("data_extraction_rules.xml")
        for (nome in sensiveis) {
            assertEquals(
                "$nome deveria estar excluído no cloud-backup e no device-transfer:\n$xml",
                2,
                ocorrencias(xml, "path=\"$nome\"")
            )
        }
    }

    @Test
    fun asPreferenciasPodemViajarNoBackup() {
        // Guardar contra alguém "resolvendo" o problema excluindo o `pulsa_settings.xml`
        // inteiro: as preferências da pessoa são trabalho manual (o PIN de children e o
        // token de reconhecimento foram para as prefs cifradas — ver
        // `Settings.secretKeys`).
        val backup = leXml("backup_rules.xml")
        val extracao = leXml("data_extraction_rules.xml")
        assertFalse("pulsa_settings.xml não deveria ficar de fora:\n$backup", backup.contains("path=\"pulsa_settings.xml\""))
        assertFalse("pulsa_settings.xml não deveria ficar de fora:\n$extracao", extracao.contains("path=\"pulsa_settings.xml\""))
    }

    @Test
    fun osSecretsNaoSaoCifradosPeloBackup() {
        // Guarda contra alguém "resolvendo" o problema tirando o `pulsa_secrets.xml` da
        // lista e acreditando que o backup é seguro: a MasterKey do Keystore não viaja,
        // então o arquivo limpo seria pior (cifrado que ninguém consegue abrir).
        val xml = leXml("backup_rules.xml")
        assertTrue(xml.contains("pulsa_secrets.xml"))
    }

    @Test
    fun tokenDeReconhecimentoEPinDeCriancaSaoSecrets() {
        // São as duas credenciais que moravam no `pulsa_settings.xml` — o arquivo que
        // agora viaja no backup. Se saírem da lista de secrets, voltam a ir em texto
        // puro para o backup do Google.
        assertTrue(Settings.secretKeys.contains("rec_token"))
        assertTrue(Settings.secretKeys.contains("kids_pin"))
    }

    private fun ocorrencias(texto: String, agulha: String): Int {
        var n = 0
        var i = texto.indexOf(agulha)
        while (i >= 0) {
            n++
            i = texto.indexOf(agulha, i + agulha.length)
        }
        return n
    }

    /**
     * Acha o XML do recurso subindo a árvore de diretórios.
     *
     * O teste roda com o diretório de trabalho na raiz do projeto (verificação local) e na
     * pasta do módulo (Gradle) — daí a busca pelos dois caminhos e a subida até achar.
     */
    private fun leXml(nome: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (relativo in listOf("app/src/main/res/xml/$nome", "src/main/res/xml/$nome")) {
                val arquivo = File(dir, relativo)
                if (arquivo.exists()) return arquivo.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("não achei res/xml/$nome a partir de ${File("").absolutePath}")
    }
}
