package com.pulsa.player.core

import android.content.Context

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

    /** Versão atual do esquema de dados. Suba este valor junto com cada vX. */
    private const val CURRENT = 1

    /** Lista de migrações, da mais antiga para a mais nova. */
    private val MIGRATIONS: List<Pair<Int, (Context) -> Unit>> = listOf(
        // Data version 1: base (schema atual do app 3.x). Nada a fazer.
        1 to { ctx -> Unit }
    )

    fun run(context: Context) {
        try {
            val sp = Settings.dataPrefs(context)
            val current = sp.getInt(KEY_DATA_VERSION, 0)
            for ((version, migration) in MIGRATIONS) {
                if (version <= current) continue
                migration(context)
                sp.edit().putInt(KEY_DATA_VERSION, version).apply()
            }
            // Garante o registro mesmo se CURRENT ainda não tiver item.
            if (current < CURRENT) {
                sp.edit().putInt(KEY_DATA_VERSION, CURRENT).apply()
            }
        } catch (t: Throwable) {
        }
    }
}
