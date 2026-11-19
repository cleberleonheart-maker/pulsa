package com.pulsa.player.core

import android.content.SharedPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DataStorePreferencesTest {

    private class FakePersistence : PreferencePersistence {
        val writes = mutableListOf<PreferenceWrite>()
        val pendingApplyResults = mutableListOf<(Boolean) -> Unit>()
        var commitOutcome = PreferenceCommitOutcome.SUCCESS
        var applyOutcome = true
        var completeApplyImmediately = true

        override fun apply(write: PreferenceWrite, onResult: (Boolean) -> Unit) {
            writes.add(write)
            if (completeApplyImmediately) onResult(applyOutcome) else pendingApplyResults.add(onResult)
        }

        override fun commit(write: PreferenceWrite): PreferenceCommitOutcome {
            writes.add(write)
            return commitOutcome
        }
    }

    @Test
    fun refreshNaoApagaEscritaOtimistaPendente() {
        val persistence = FakePersistence()
        persistence.completeApplyImmediately = false
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_profile",
            initialValues = mapOf("nick" to PreferenceValue.Text("Virgin")),
            persistence = persistence
        )

        prefs.edit().putString("nick", "Victor").apply()

        prefs.refresh(mapOf("nick" to PreferenceValue.Text("Virgin")))

        assertEquals("Victor", prefs.getString("nick", null))
    }

    @Test
    fun refreshAplicaValorNovoQuandoNaoHaEscritaPendente() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_profile",
            initialValues = mapOf("nick" to PreferenceValue.Text("Virgin")),
            persistence = persistence
        )

        prefs.refresh(mapOf("nick" to PreferenceValue.Text("Maria")))

        assertEquals("Maria", prefs.getString("nick", null))
    }

    @Test
    fun mantemTiposEIsolaNamespaces() {
        val persistence = FakePersistence()
        val settings = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = mapOf("language" to PreferenceValue.Text("pt-BR")),
            persistence = persistence
        )
        val profile = DataStoreSharedPreferences(
            namespace = "pulsa_profile",
            initialValues = emptyMap(),
            persistence = persistence
        )

        settings.edit()
            .putString("language", "en")
            .putInt("depth", 60)
            .putLong("position", 1234L)
            .putFloat("tone", 0.75f)
            .putBoolean("enabled", true)
            .putStringSet("genres", mutableSetOf("rock", "pop"))
            .apply()
        profile.edit().putString("nick", "Virgin").apply()

        assertEquals("en", settings.getString("language", null))
        assertEquals(60, settings.getInt("depth", 0))
        assertEquals(1234L, settings.getLong("position", 0L))
        assertEquals(0.75f, settings.getFloat("tone", 0f))
        assertTrue(settings.getBoolean("enabled", false))
        assertEquals(setOf("rock", "pop"), settings.getStringSet("genres", null))
        assertEquals("Virgin", profile.getString("nick", null))
        assertNull(settings.getString("nick", null))
        assertFalse(settings.getAll().containsKey("nick"))
    }

    @Test
    fun applyAtualizaCacheImediatamenteEEnfileiraEscritaAtomica() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_profile",
            initialValues = emptyMap(),
            persistence = persistence
        )

        prefs.edit()
            .putString("nick", "Victor")
            .putBoolean("avatar_masculino", true)
            .apply()

        assertEquals("Victor", prefs.getString("nick", null))
        assertTrue(prefs.getBoolean("avatar_masculino", false))
        assertEquals(1, persistence.writes.size)
        assertEquals(setOf("nick", "avatar_masculino"), persistence.writes.single().values.keys)
    }

    @Test
    fun removeClearECommitPreservamOContrato() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_update",
            initialValues = mapOf(
                "attempted_name" to PreferenceValue.Text("5.12.39"),
                "attempted_code" to PreferenceValue.LongNumber(236L)
            ),
            persistence = persistence
        )

        assertTrue(prefs.contains("attempted_name"))
        prefs.edit().remove("attempted_name").apply()
        assertFalse(prefs.contains("attempted_name"))

        val committed = prefs.edit().putLong("attempted_code", 237L).commit()
        assertTrue(committed)
        assertEquals(237L, prefs.getLong("attempted_code", 0L))
        assertTrue(persistence.writes.last().values.containsKey("attempted_code"))

        prefs.edit().clear().apply()
        assertTrue(prefs.getAll().isEmpty())
    }

    @Test
    fun listenersRecebemAsChavesAlteradas() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_blacklist",
            initialValues = emptyMap(),
            persistence = persistence
        )
        val changed = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changed.add(key) }

        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putBoolean("blacklist_warned_once", true).apply()
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
        prefs.edit().putBoolean("blacklist_warned_once", false).apply()

        assertEquals(listOf("blacklist_warned_once"), changed)
    }

    @Test
    fun namespacesCodificamChavesSemMisturarValores() {
        val encoded = DataStoreNamespaces.encode("pulsa_settings", "language")
        assertEquals("pulsa_settings" to "language", DataStoreNamespaces.decode(encoded))
        assertNull(DataStoreNamespaces.decode(DataStoreNamespaces.MIGRATION_MARKER))
    }

    @Test
    fun commitComFalhaRestauraOCache() {
        val persistence = FakePersistence()
        persistence.commitOutcome = PreferenceCommitOutcome.FAILURE
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = mapOf("language" to PreferenceValue.Text("pt-BR")),
            persistence = persistence
        )

        val committed = prefs.edit().putString("language", "en").commit()

        assertFalse(committed)
        assertEquals("pt-BR", prefs.getString("language", null))
    }

    @Test
    fun applyComFalhaNaoReverteWriteMaisNovo() {
        val persistence = FakePersistence()
        persistence.completeApplyImmediately = false
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = mapOf("language" to PreferenceValue.Text("pt-BR")),
            persistence = persistence
        )

        prefs.edit().putString("language", "en").apply()
        prefs.edit().putString("language", "es").apply()
        persistence.pendingApplyResults[0](false)
        persistence.pendingApplyResults[1](true)

        assertEquals("es", prefs.getString("language", null))
    }

    @Test
    fun snapshotDeMigracaoNaoSobrescreveWriteMaisNovo() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = emptyMap(),
            persistence = persistence
        )
        val migrationRevision = PreferenceWriteRevision.next()
        val migration = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("pt-BR")),
            revision = migrationRevision
        )

        prefs.mergeMigratedValues(migration, setOf("language"))
        prefs.edit().putString("language", "en").apply()
        prefs.mergeMigratedValues(migration, setOf("language"))

        assertEquals("en", prefs.getString("language", null))
    }

    @Test
    fun remocaoEClearPersistidosImpedemRessurreicaoPelaMigracao() {
        val preferences = mutablePreferencesOf()
        val removal = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to null),
            revision = 7L
        )
        val migration = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("pt-BR"))
        )

        preferences.applyWrite(removal)

        assertFalse(preferences.contains(stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language"))))
        assertTrue(
            preferences.contains(
                longPreferencesKey(DataStoreNamespaces.revisionKey("pulsa_settings", "language"))
            )
        )
        assertTrue(preferences.applyWriteIfAbsent(migration).isEmpty())

        val cleared = mutablePreferencesOf()
        cleared.applyWrite(
            PreferenceWrite(
                namespace = "pulsa_settings",
                clear = true,
                values = emptyMap(),
                revision = 8L
            )
        )
        assertTrue(cleared.applyWriteIfAbsent(migration).isEmpty())
    }

    @Test
    fun falhaAposMigracaoRestauraValorPersistidoMaisRecente() {
        val persistence = FakePersistence()
        persistence.completeApplyImmediately = false
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = emptyMap(),
            persistence = persistence
        )
        val migration = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("pt-BR")),
            revision = PreferenceWriteRevision.next()
        )

        prefs.edit().putString("language", "en").apply()
        prefs.mergeMigratedValues(migration, setOf("language"))
        persistence.pendingApplyResults.single()(false)

        assertEquals("pt-BR", prefs.getString("language", null))
    }

    @Test
    fun secretNaoEntraNoStoreNemNoCache() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = emptyMap(),
            persistence = persistence,
            blockedKeys = setOf("telemetry_token")
        )

        val committed = prefs.edit().putString("telemetry_token", "segredo").commit()

        assertFalse(committed)
        assertNull(prefs.getString("telemetry_token", null))
        assertTrue(persistence.writes.isEmpty())
    }

    @Test
    fun chaveBloqueadaNaoDescartaAsOutrasEdicoes() {
        val persistence = FakePersistence()
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = emptyMap(),
            persistence = persistence,
            blockedKeys = setOf("telemetry_token")
        )

        val committed = prefs.edit()
            .putString("telemetry_token", "segredo")
            .putString("language", "en")
            .commit()

        assertTrue(committed)
        assertEquals("en", prefs.getString("language", null))
        assertNull(prefs.getString("telemetry_token", null))
        assertEquals(setOf("language"), persistence.writes.single().values.keys)
    }

    @Test
    fun commitComResultadoDesconhecidoRestauraOCache() {
        val persistence = FakePersistence()
        persistence.commitOutcome = PreferenceCommitOutcome.UNKNOWN
        val prefs = DataStoreSharedPreferences(
            namespace = "pulsa_settings",
            initialValues = mapOf("language" to PreferenceValue.Text("pt-BR")),
            persistence = persistence
        )

        val committed = prefs.edit().putString("language", "en").commit()

        assertFalse(committed)
        assertEquals("pt-BR", prefs.getString("language", null))
    }

    @Test
    fun fallbackLegadoSubstituiERemoveValoresMaisNovos() {
        val current = DecodedSnapshot(
            values = mapOf(
                "pulsa_settings" to mapOf(
                    "language" to PreferenceValue.Text("pt-BR"),
                    "removed" to PreferenceValue.Text("velho")
                )
            ),
            revisions = emptyMap()
        )
        val legacy = LegacySnapshot(
            values = mapOf(
                "pulsa_settings" to mapOf("language" to PreferenceValue.Text("en"))
            ),
            fallbackKeys = mapOf("pulsa_settings" to setOf("language", "removed")),
            fallbackGenerations = mapOf("pulsa_settings" to 1L)
        )

        val merged = DataStorePreferences.mergeSnapshots(current, legacy)

        assertEquals(PreferenceValue.Text("en"), merged["pulsa_settings"]?.get("language"))
        assertFalse(merged["pulsa_settings"].orEmpty().containsKey("removed"))
    }

    @Test
    fun valorRemovidoNoDataStoreNaoRessuscitaDoXmlLegado() {
        val current = DecodedSnapshot(
            values = mapOf("pulsa_settings" to emptyMap()),
            revisions = mapOf("pulsa_settings" to mapOf("language" to 7L))
        )
        val legacy = LegacySnapshot(
            values = mapOf("pulsa_settings" to mapOf("language" to PreferenceValue.Text("en"))),
            fallbackKeys = emptyMap()
        )

        val merged = DataStorePreferences.mergeSnapshots(current, legacy)

        assertFalse(merged["pulsa_settings"].orEmpty().containsKey("language"))
    }

    @Test
    fun fallbackJaConsumidoNaoSobrepoeValorMaisNovo() {
        val current = DecodedSnapshot(
            values = mapOf("pulsa_settings" to mapOf("language" to PreferenceValue.Text("pt-BR"))),
            revisions = emptyMap(),
            consumedFallbackGenerations = mapOf("pulsa_settings" to 7L)
        )
        val legacy = LegacySnapshot(
            values = mapOf("pulsa_settings" to mapOf("language" to PreferenceValue.Text("en"))),
            fallbackKeys = mapOf("pulsa_settings" to setOf("language")),
            fallbackGenerations = mapOf("pulsa_settings" to 7L)
        )

        val merged = DataStorePreferences.mergeSnapshots(current, legacy)

        assertEquals(PreferenceValue.Text("pt-BR"), merged["pulsa_settings"]?.get("language"))
    }

    @Test
    fun migracaoForcadaSobrescreveERemoveNoDataStore() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language")) to "pt-BR",
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "removed")) to "velho"
        )
        val write = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf(
                "language" to PreferenceValue.Text("en"),
                "removed" to null
            ),
            overwriteKeys = setOf("language", "removed"),
            fallbackGeneration = 7L
        )

        val inserted = preferences.applyWriteIfAbsent(write)

        assertEquals(setOf("language", "removed"), inserted)
        assertEquals("en", preferences[stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language"))])
        assertFalse(preferences.contains(stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "removed"))))
    }

    @Test
    fun migracaoForcadaSemFallbackSobrescreveSempre() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language")) to "pt-BR"
        )
        val write = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("en")),
            overwriteKeys = setOf("language")
        )

        val inserted = preferences.applyWriteIfAbsent(write)

        assertEquals(setOf("language"), inserted)
        assertEquals("en", preferences[stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language"))])
    }

    @Test
    fun fallbackConsumidoNaoSobrepoeChavesDeFallbackMasAceitaExternalizacao() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language")) to "pt-BR",
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "idioma")) to "pt-BR",
            longPreferencesKey(DataStoreNamespaces.consumedFallbackKey("pulsa_settings")) to 7L
        )
        val write = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf(
                "language" to PreferenceValue.Text("en"),
                "idioma" to PreferenceValue.Text("en")
            ),
            overwriteKeys = setOf("idioma"),
            fallbackOverwriteKeys = setOf("language"),
            fallbackGeneration = 7L
        )

        val inserted = preferences.applyWriteIfAbsent(write)

        assertEquals(setOf("idioma"), inserted)
        assertEquals("pt-BR", preferences[stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language"))])
        assertEquals("en", preferences[stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "idioma"))])
    }

    @Test
    fun fallbackJaConsumidoNaoEForcadoNovamenteNoDataStore() {
        val preferences = mutablePreferencesOf(
            stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language")) to "pt-BR",
            longPreferencesKey(DataStoreNamespaces.consumedFallbackKey("pulsa_settings")) to 7L
        )
        val write = PreferenceWrite(
            namespace = "pulsa_settings",
            clear = false,
            values = mapOf("language" to PreferenceValue.Text("en")),
            fallbackOverwriteKeys = setOf("language"),
            fallbackGeneration = 7L
        )

        val inserted = preferences.applyWriteIfAbsent(write)

        assertTrue(inserted.isEmpty())
        assertEquals("pt-BR", preferences[stringPreferencesKey(DataStoreNamespaces.encode("pulsa_settings", "language"))])
    }
}
