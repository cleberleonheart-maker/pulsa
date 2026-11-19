package com.pulsa.player.core

import android.content.Context
import android.util.Log

/**
 * Canaleta de migração de dados entre versões do app.
 *
 * Cada versão nova que precise transformar dados locais (banco, prefs,
 * arquivos) adiciona um item em [MIGRATIONS] com o valor que a [CURRENT]
 * passa a assumir ao final. As migrações rodam UMA vez por aparelho, em
 * ordem, na primeira execução após o update.
 */
object Migrations {

    private const val KEY_DATA_VERSION = "data_version"
    private const val TAG = "PulsaMigrations"

    /** Versão atual do esquema de dados. Suba este valor junto com cada vX. */
    internal const val CURRENT = 3

    /** Lista de migrações, da mais antiga para a mais nova. */
    private val MIGRATIONS: List<Pair<Int, (Context) -> Boolean>> = listOf(
        1 to { _ -> true },
        2 to { ctx -> Settings.migrateLegacySecretsResult(ctx) }
    )

    fun run(context: Context) {
        try {
            DataStorePreferences.initialize(context)
            val legacyStores = try {
                mapOf(
                    DataStorePreferences.SETTINGS_NAMESPACE to
                        LegacyPreferencesFile.read(context, DataStorePreferences.SETTINGS_NAMESPACE)
                )
            } catch (t: Throwable) {
                Log.e(TAG, "falha ao ler data_version legado", t)
                emptyMap()
            }
            val sp = Settings.dataPrefs(context)
            val datastoreVersion = sp.getInt(KEY_DATA_VERSION, 0)
            val legacyVersion = LegacyMigrationPlan.dataVersion(legacyStores)
            var current = MigrationPlan.effectiveDataVersion(datastoreVersion, legacyVersion)
            for ((version, migration) in MIGRATIONS) {
                if (version <= current) continue
                if (!migration(context)) {
                    Log.e(TAG, "migracao $version falhou")
                    break
                }
                sp.edit().putInt(KEY_DATA_VERSION, version).apply()
                current = version
            }
            val preferencesReady = LegacyPreferencesMigration.migrate(context, Settings.secretKeys.toSet())
            if (!preferencesReady) {
                Log.e(TAG, "migracao de preferencias legadas nao concluida")
            }
            if (preferencesReady && current >= CURRENT - 1 && current < CURRENT) {
                sp.edit().putInt(KEY_DATA_VERSION, CURRENT).apply()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "falha ao executar migracoes", t)
        }
    }
}

internal object MigrationPlan {
    fun effectiveDataVersion(datastoreVersion: Int, legacyVersion: Int): Int =
        maxOf(datastoreVersion.coerceAtLeast(0), legacyVersion.coerceAtLeast(0))

    fun shouldCleanupLegacy(markerPresent: Boolean, legacyFilesRemain: Boolean): Boolean =
        markerPresent && legacyFilesRemain
}
