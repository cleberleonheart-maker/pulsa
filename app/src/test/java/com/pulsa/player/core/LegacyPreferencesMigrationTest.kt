package com.pulsa.player.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyPreferencesMigrationTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun planoExcluiSecretsEPreservaTiposPorNamespace() {
        val secrets = Settings.secretKeys.associateWith { PreferenceValue.Text("segredo-$it") }
        val stores = mapOf(
            DataStorePreferences.SETTINGS_NAMESPACE to mapOf(
                "language" to PreferenceValue.Text("pt-BR"),
                "depth" to PreferenceValue.Integer(60),
                "position" to PreferenceValue.LongNumber(42L),
                "tone" to PreferenceValue.Decimal(0.5f),
                "enabled" to PreferenceValue.BooleanValue(true),
                "genres" to PreferenceValue.TextSet(setOf("rock")),
                "data_version" to PreferenceValue.Integer(2)
            ) + secrets,
            "pulsa_profile" to mapOf("nick" to PreferenceValue.Text("Virgin"))
        )

        val writes = LegacyMigrationPlan.writes(stores, Settings.secretKeys.toSet())
        val settings = writes.single { it.namespace == DataStorePreferences.SETTINGS_NAMESPACE }
        val profile = writes.single { it.namespace == "pulsa_profile" }

        Settings.secretKeys.forEach { key -> assertFalse(settings.values.containsKey(key)) }
        assertEquals(PreferenceValue.Text("pt-BR"), settings.values["language"])
        assertEquals(PreferenceValue.Integer(60), settings.values["depth"])
        assertEquals(PreferenceValue.LongNumber(42L), settings.values["position"])
        assertEquals(PreferenceValue.Decimal(0.5f), settings.values["tone"])
        assertEquals(PreferenceValue.BooleanValue(true), settings.values["enabled"])
        assertEquals(PreferenceValue.TextSet(setOf("rock")), settings.values["genres"])
        assertEquals(PreferenceValue.Integer(2), settings.values["data_version"])
        assertEquals(PreferenceValue.Text("Virgin"), profile.values["nick"])
    }

    @Test
    fun todosOsStoresLegadosNaoSensiveisTemNamespace() {
        assertEquals(
            setOf(
                "pulsa_settings",
                "pulsa_profile",
                "pulsa_blacklist",
                "pulsa_radio_stations",
                "pulsa_stream_history",
                "pulsa_dj_memory",
                "subtitle_config",
                "pulsa_update",
                "pulsa_last_changelog",
                "pulsa_last_installed"
            ),
            DataStorePreferences.LEGACY_NAMESPACES.toSet()
        )
    }

    @Test
    fun migracaoSoRodaQuandoMarcadorNaoExiste() {
        assertTrue(LegacyMigrationPlan.needsMigration(false))
        assertFalse(LegacyMigrationPlan.needsMigration(true))
    }

    @Test
    fun xmlLegadoPreservaTodosOsTiposAceitos() {
        val xml = """
            <?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            <map>
                <boolean name="enabled" value="true" />
                <int name="depth" value="60" />
                <long name="position" value="1234" />
                <float name="tone" value="0.75" />
                <string name="language">pt-BR</string>
                <set name="genres">
                    <string>rock</string>
                    <string>pop</string>
                </set>
            </map>
        """.trimIndent()

        val values = LegacyPreferencesXml.parse(xml)

        assertEquals(PreferenceValue.BooleanValue(true), values["enabled"])
        assertEquals(PreferenceValue.Integer(60), values["depth"])
        assertEquals(PreferenceValue.LongNumber(1234L), values["position"])
        assertEquals(PreferenceValue.Decimal(0.75f), values["tone"])
        assertEquals(PreferenceValue.Text("pt-BR"), values["language"])
        assertEquals(PreferenceValue.TextSet(setOf("rock", "pop")), values["genres"])
    }

    @Test
    fun xmlLegadoEscapaTextoEVoltaAoMismoValor() {
        val values = mapOf(
            "language" to PreferenceValue.Text("pt \u0026 <BR>"),
            "quote" to PreferenceValue.Text("\"legado\"")
        )

        val parsed = LegacyPreferencesXml.parse(LegacyPreferencesXml.write(values))

        assertEquals(PreferenceValue.Text("pt \u0026 <BR>"), parsed["language"])
        assertEquals(PreferenceValue.Text("\"legado\""), parsed["quote"])
    }

    @Test
    fun escritaXmlPreservaTiposComOrdemDeterministica() {
        val values = mapOf(
            "genres" to PreferenceValue.TextSet(linkedSetOf("rock", "pop")),
            "enabled" to PreferenceValue.BooleanValue(true),
            "depth" to PreferenceValue.Integer(60),
            "position" to PreferenceValue.LongNumber(42L),
            "tone" to PreferenceValue.Decimal(0.5f),
            "language" to PreferenceValue.Text("pt-BR")
        )

        val xml = LegacyPreferencesXml.write(values)
        val parsed = LegacyPreferencesXml.parse(xml)

        assertTrue(xml.indexOf("<string>pop</string>") < xml.indexOf("<string>rock</string>"))
        assertEquals(values.keys.toSet(), parsed.keys)
        values.forEach { (key, value) -> assertEquals(value, parsed[key]) }
    }

    @Test
    fun leituraRecuperaBackupQuandoPrincipalEstaCorrompido() {
        val directory = temporaryFolder.newFolder("prefs")
        val file = File(directory, "pulsa_settings.xml")
        val renamer = LegacyFileRenamer { source, target -> source.renameTo(target) }
        val original = mapOf("language" to PreferenceValue.Text("pt-BR"))
        val updated = mapOf("language" to PreferenceValue.Text("en"))

        assertTrue(LegacyPreferencesFileStore.write(file, original, renamer))
        assertTrue(LegacyPreferencesFileStore.write(file, updated, renamer))
        file.writeText("<map>invalido")

        assertEquals(original, semMarcador(LegacyPreferencesFileStore.read(file)))
    }

    @Test
    fun falhaNoRenameNaoTruncaOPrincipal() {
        val directory = temporaryFolder.newFolder("prefs")
        val file = File(directory, "pulsa_settings.xml")
        val original = mapOf("language" to PreferenceValue.Text("pt-BR"))
        val updated = mapOf("language" to PreferenceValue.Text("en"))

        assertTrue(
            LegacyPreferencesFileStore.write(
                file,
                original,
                LegacyFileRenamer { source, target -> source.renameTo(target) }
            )
        )
        val failed = LegacyFileRenamer { source, target ->
            if (target == file) false else source.renameTo(target)
        }

        assertFalse(LegacyPreferencesFileStore.write(file, updated, failed))
        assertEquals(original, semMarcador(LegacyPreferencesFileStore.read(file)))
    }

    @Test
    fun fallbackPreservaSecretLegadoERejeitaSecretNovo() {
        val existing = mapOf(
            "language" to PreferenceValue.Text("pt-BR"),
            "telemetry_token" to PreferenceValue.Text("legado")
        )
        val write = PreferenceWrite(
            namespace = DataStorePreferences.SETTINGS_NAMESPACE,
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("en"))
        )
        val secretWrite = PreferenceWrite(
            namespace = DataStorePreferences.SETTINGS_NAMESPACE,
            clear = false,
            values = mapOf("telemetry_token" to PreferenceValue.Text("novo"))
        )

        val merged = LegacyMigrationPlan.mergeWrite(existing, write, setOf("telemetry_token"))

        assertEquals(PreferenceValue.Text("en"), merged["language"])
        assertEquals(PreferenceValue.Text("legado"), merged["telemetry_token"])
        assertFalse(LegacyMigrationPlan.canApplyWrite(secretWrite, setOf("telemetry_token")))
    }

    @Test
    fun dataVersionLegadoEvitaRodarEtapasConcluidas() {
        val stores = mapOf(
            DataStorePreferences.SETTINGS_NAMESPACE to mapOf(
                "data_version" to PreferenceValue.Integer(2)
            )
        )

        assertEquals(2, LegacyMigrationPlan.dataVersion(stores))
        assertEquals(2, MigrationPlan.effectiveDataVersion(0, 2))
        assertEquals(3, MigrationPlan.effectiveDataVersion(3, 2))
    }

    @Test
    fun marcadorExistenteExigeLimpezaMesmoComDataVersionAntigo() {
        assertTrue(MigrationPlan.shouldCleanupLegacy(markerPresent = true, legacyFilesRemain = true))
        assertFalse(MigrationPlan.shouldCleanupLegacy(markerPresent = false, legacyFilesRemain = true))
        assertFalse(MigrationPlan.shouldCleanupLegacy(markerPresent = true, legacyFilesRemain = false))
    }

    @Test
    fun planoPreservaChavesDoFallbackEAsRemovidas() {
        val fallbackKeys = setOf("language", "removed")
        val stores = mapOf(
            DataStorePreferences.SETTINGS_NAMESPACE to mapOf(
                "language" to PreferenceValue.Text("en"),
                DataStoreNamespaces.FALLBACK_MARKER to PreferenceValue.Text(
                    encodeFallbackKeys(fallbackKeys)
                )
            )
        )

        val write = LegacyMigrationPlan.writes(
            stores,
            Settings.secretKeys.toSet()
        ).single()

        assertEquals(PreferenceValue.Text("en"), write.values["language"])
        assertTrue(write.values.containsKey("removed"))
        assertEquals(null, write.values["removed"])
        assertEquals(fallbackKeys, write.fallbackOverwriteKeys)
        assertEquals(0L, write.fallbackGeneration ?: -1L)
    }

    @Test
    fun fallbackNoXmlRegistraAsChavesAlteradas() {
        val directory = temporaryFolder.newFolder("prefs")
        val file = File(directory, "pulsa_settings.xml")
        val original = mapOf(
            "language" to PreferenceValue.Text("pt-BR"),
            "telemetry_token" to PreferenceValue.Text("legado")
        )
        assertTrue(
            LegacyPreferencesFileStore.write(
                file,
                original,
                LegacyFileRenamer { source, target -> source.renameTo(target) }
            )
        )

        val applied = LegacyPreferencesFileStore.applyWrite(
            file,
            PreferenceWrite(
                namespace = DataStorePreferences.SETTINGS_NAMESPACE,
                clear = false,
                values = mapOf("language" to PreferenceValue.Text("en"))
            ),
            protectedKeys = setOf("telemetry_token"),
            renamer = LegacyFileRenamer { source, target -> source.renameTo(target) }
        )

        assertTrue(applied)
        val values = LegacyPreferencesFileStore.read(file)
        assertEquals(PreferenceValue.Text("en"), values["language"])
        assertEquals(PreferenceValue.Text("legado"), values["telemetry_token"])
        assertEquals(
            setOf("language"),
            decodeFallbackKeys((values[DataStoreNamespaces.FALLBACK_MARKER] as? PreferenceValue.Text)?.value)
        )
        assertTrue(values[DataStoreNamespaces.FALLBACK_GENERATION_MARKER] is PreferenceValue.LongNumber)
    }

    @Test
    fun remocaoDeSecretApagaOCopiaLegadaComTextoClaro() {
        val directory = temporaryFolder.newFolder("prefs")
        val file = File(directory, "pulsa_settings.xml")
        assertTrue(
            LegacyPreferencesFileStore.write(
                file,
                mapOf("telemetry_token" to PreferenceValue.Text("legado")),
                LegacyFileRenamer { source, target -> source.renameTo(target) }
            )
        )

        val removed = LegacyPreferencesFileStore.remove(
            file,
            setOf("telemetry_token"),
            LegacyFileRenamer { source, target -> source.renameTo(target) }
        )

        assertTrue(removed)
        assertFalse(LegacyPreferencesFileStore.read(file).containsKey("telemetry_token"))
        assertFalse(File(directory, "pulsa_settings.xml.bak").exists())
    }

    private fun semMarcador(values: Map<String, PreferenceValue>): Map<String, PreferenceValue> =
        values.filterKeys { it != DataStoreNamespaces.MIRROR_MARKER }

    @Test
    fun chavesDoFallbackComNomesEspeciaisVoltamIguais() {
        val keys = setOf("language", "a,b", "c\\d")

        assertEquals(keys, decodeFallbackKeys(encodeFallbackKeys(keys)))
    }

    @Test
    fun xmlEspelhadoRecebeMarcadorDeIntegridade() {
        val directory = temporaryFolder.newFolder("prefs")
        val file = File(directory, "pulsa_settings.xml")
        assertTrue(
            LegacyPreferencesFileStore.write(
                file,
                mapOf("language" to PreferenceValue.Text("pt-BR")),
                LegacyFileRenamer { source, target -> source.renameTo(target) }
            )
        )

        val values = LegacyPreferencesFileStore.read(file)
        val marker = values[DataStoreNamespaces.MIRROR_MARKER] as? PreferenceValue.Text

        assertTrue(marker != null)
        assertEquals(
            marker?.value,
            LegacyPreferencesXml.fingerprint(values.filterKeys { it != DataStoreNamespaces.MIRROR_MARKER })
        )
    }

    @Test
    fun alteracaoFeitaPorVersaoAntigaSobrepoeOValorNoDataStore() {
        val namespace = DataStorePreferences.SETTINGS_NAMESPACE
        val legacyValues = mapOf(
            "language" to PreferenceValue.Text("pt-BR"),
            "tone" to PreferenceValue.Integer(3)
        )
        val mirrored = legacyValues + (
            DataStoreNamespaces.MIRROR_MARKER to PreferenceValue.Text(
                LegacyPreferencesXml.fingerprint(legacyValues)
            )
            )
        val changedByOldVersion = mirrored + ("language" to PreferenceValue.Text("en"))
        val currentValues = mapOf(
            namespace to mapOf(
                "language" to PreferenceValue.Text("pt-BR"),
                "tone" to PreferenceValue.Integer(3)
            )
        )

        val write = LegacyMigrationPlan.writes(
            mapOf(namespace to changedByOldVersion),
            Settings.secretKeys.toSet(),
            currentValues
        ).single()

        assertEquals(setOf("language"), write.overwriteKeys)
        assertEquals(PreferenceValue.Text("en"), write.values["language"])
    }

    @Test
    fun xmlSemMarcadorNaoEhConsideradoAlteracaoExterna() {
        val namespace = DataStorePreferences.SETTINGS_NAMESPACE
        val legacyValues = mapOf("language" to PreferenceValue.Text("pt-BR"))

        val write = LegacyMigrationPlan.writes(
            mapOf(namespace to legacyValues),
            Settings.secretKeys.toSet(),
            mapOf(namespace to mapOf("language" to PreferenceValue.Text("en")))
        ).single()

        assertTrue(write.overwriteKeys.isEmpty())
        assertEquals(PreferenceValue.Text("pt-BR"), write.values["language"])
    }

    @Test
    fun namespaceSemConfiancaNaoUsaAlteracaoExterna() {
        val namespace = DataStorePreferences.SETTINGS_NAMESPACE
        val legacyValues = mapOf("language" to PreferenceValue.Text("pt-BR"))
        val mirrored = legacyValues + (
            DataStoreNamespaces.MIRROR_MARKER to PreferenceValue.Text(
                LegacyPreferencesXml.fingerprint(legacyValues)
            )
            )
        val changedByOldVersion = mirrored + ("language" to PreferenceValue.Text("en"))

        val write = LegacyMigrationPlan.writes(
            mapOf(namespace to changedByOldVersion),
            Settings.secretKeys.toSet(),
            mapOf(namespace to mapOf("language" to PreferenceValue.Text("pt-BR"))),
            trustedNamespaces = emptySet()
        ).single()

        assertTrue(write.overwriteKeys.isEmpty())
        assertEquals(PreferenceValue.Text("pt-BR"), write.values["language"])
    }

    @Test
    fun xmlEspelhadoSemAlteracaoNaoSobrescreveODataStore() {
        val namespace = DataStorePreferences.SETTINGS_NAMESPACE
        val legacyValues = mapOf("language" to PreferenceValue.Text("pt-BR"))
        val mirrored = legacyValues + (
            DataStoreNamespaces.MIRROR_MARKER to PreferenceValue.Text(
                LegacyPreferencesXml.fingerprint(legacyValues)
            )
            )

        val write = LegacyMigrationPlan.writes(
            mapOf(namespace to mirrored),
            Settings.secretKeys.toSet(),
            mapOf(namespace to mapOf("language" to PreferenceValue.Text("en")))
        ).single()

        assertTrue(write.overwriteKeys.isEmpty())
    }
}
