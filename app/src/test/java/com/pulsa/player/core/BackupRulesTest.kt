package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * O que o backup do Google pode levar.
 *
 * Todo o domínio `sharedpref` fica fora do backup:
 *
 * - `pulsa_secrets.xml` — chave do Gemini, do Last.fm, do Podcast Index, token de admin, o
 *   da nuvem, o token de reconhecimento e o PIN de children. São cifrados com a MasterKey do
 *   Keystore, e a MasterKey **não** viaja: no aparelho novo a leitura falha e o app entra em
 *   reset;
 * - `pulsa_account.xml` — a conta;
 * - os XMLs legados das preferências — ficam só como cópia local de recuperação.
 *
 * As preferências não sensíveis estão no DataStore, no domínio `files`, e são incluídas
 * por padrão.
 *
 * A regra mora em XML (`res/xml`), não em código — então o teste lê o XML. É o único jeito
 * de a exclusão não virar "achismo" depois de uma edição de resource.
 */
class BackupRulesTest {

    private val exclusaoSharedPref = "domain=\"sharedpref\" path=\".\""

    @Test
    fun backupRulesExcluemOsArquivosSensiveis() {
        val xml = leXml("backup_rules.xml")
        assertTrue(
            "backup_rules.xml deveria excluir todo o sharedpref:\n$xml",
            xml.contains(exclusaoSharedPref)
        )
    }

    @Test
    fun dataExtractionRulesExcluemOsArquivosSensiveis() {
        val xml = leXml("data_extraction_rules.xml")
        assertTrue(
            "data_extraction_rules.xml deveria excluir todo o sharedpref:\n$xml",
            xml.contains(exclusaoSharedPref)
        )
    }

    @Test
    fun cloudBackupETransferenciaExcluemOsMesmos() {
        val xml = leXml("data_extraction_rules.xml")
        assertEquals(
            "sharedpref deveria estar excluído no cloud-backup e no device-transfer:\n$xml",
            2,
            ocorrencias(xml, exclusaoSharedPref)
        )
    }

    @Test
    fun oDataStoreNaoEhExcluidoDoBackup() {
        val backup = leXml("backup_rules.xml")
        val extracao = leXml("data_extraction_rules.xml")
        assertFalse("o DataStore não deveria ficar de fora:\n$backup", backup.contains("domain=\"file\" path=\"datastore"))
        assertFalse("o DataStore não deveria ficar de fora:\n$extracao", extracao.contains("domain=\"file\" path=\"datastore"))
    }

    @Test
    fun osSecretsNaoSaoCifradosPeloBackup() {
        val xml = leXml("backup_rules.xml")
        assertTrue(xml.contains(exclusaoSharedPref))
        assertFalse(xml.contains("domain=\"file\" path=\".\""))
    }

    @Test
    fun tokenDeReconhecimentoEPinDeCriancaSaoSecrets() {
        // São as duas credenciais que moravam no `pulsa_settings.xml`. Se saírem da lista
        // de secrets, voltam a ir em texto puro para o backup do Google.
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
