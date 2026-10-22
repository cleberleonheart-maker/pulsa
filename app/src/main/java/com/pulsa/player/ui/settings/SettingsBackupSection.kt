package com.pulsa.player.ui.settings

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Settings
import com.pulsa.player.data.PlaylistBackup
import com.pulsa.player.data.PlaylistDb
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjMemory
import org.json.JSONObject

/**
 * Backup e restauração — exporta/importa aprendizado, EQ, espacial e biblioteca.
 */

internal fun SettingsActivity.buildBackup(): String {
    val learn = DjLearn.snapshot(applicationContext)
    val memoryFacts = DjMemory.exportFacts(applicationContext)
    val p = Settings.dataPrefs(applicationContext)
    val eq = JSONObject().apply {
        put("equalizer_on", p.getBoolean("equalizer_on", false))
        put("custom_eq_on", p.getBoolean("custom_eq_on", false))
        put("custom_eq_bands", p.getString("custom_eq_bands", "0,0,0,0,0"))
    }
    // O 3D e o surround vão juntos: um usuário que reinstalou o app perde o ajuste junto
    // com o botão, e sem isto o som volta diferente do que ele deixou.
    val spatial = JSONObject().apply {
        put("spatial_3d", p.getBoolean("spatial_3d", false))
        put("spatial_3d_depth", p.getInt("spatial_3d_depth", 60))
        put("spatial_surround", p.getBoolean("spatial_surround", false))
        put("spatial_surround_intensity", p.getInt("spatial_surround_intensity", 50))
    }
    return JSONObject().apply {
        put("app", "pulsa")
        // 2 = acrescenta o bloco de biblioteca (playlists, favoritas, correções de nome).
        // Um `pulsa-backup.json` da v1 continua restaurando normalmente: o `applyRestore`
        // lê o bloco novo com `optJSONObject`, que devolve `null` quando ele não existe.
        put("backupVersion", 2)
        put("learn", learn)
        put("memoryFacts", memoryFacts)
        put("eq", eq)
        put("spatial", spatial)
        put("library", PlaylistBackup.export(applicationContext, PlaylistDb.get(applicationContext)))
    }.toString()
}

/**
 * O que o restore devolve: mudou alguma coisa que afeta o áudio?
 *
 * Antes disto era um `Boolean` e o chamador fazia `if (applied) Playback.refreshFx()`.
 * Com o bloco de biblioteca no meio, um backup só de playlists marcaria `true` e forçaria
 * um `refreshFx()` à toa — inofensivo, mas é o tipo de coisa que finge ter efeito.
 */
internal data class RestoreOutcome(
    val settingsChanged: Boolean,
    val report: PlaylistBackup.Report
)

internal fun SettingsActivity.applyRestore(text: String): RestoreOutcome {
    val root = runCatching { JSONObject(text) }.getOrNull()
        ?: return RestoreOutcome(false, PlaylistBackup.Report())
    if (root.optString("app") != "pulsa") {
        return RestoreOutcome(false, PlaylistBackup.Report())
    }
    var changed = false
    root.optJSONObject("learn")?.let {
        changed = DjLearn.mergeRemote(applicationContext, it) || changed
    }
    val facts = root.optString("memoryFacts", "")
    if (facts.isNotBlank()) {
        changed = DjMemory.importFacts(applicationContext, facts) || changed
    }
    root.optJSONObject("eq")?.let { eq ->
        Settings.dataPrefs(applicationContext).edit()
            .putBoolean("equalizer_on", eq.optBoolean("equalizer_on", false))
            .putBoolean("custom_eq_on", eq.optBoolean("custom_eq_on", false))
            .putString("custom_eq_bands", eq.optString("custom_eq_bands", "0,0,0,0,0"))
            .apply()
        changed = true
    }
    root.optJSONObject("spatial")?.let { spatial ->
        Settings.dataPrefs(applicationContext).edit()
            .putBoolean("spatial_3d", spatial.optBoolean("spatial_3d", false))
            .putInt("spatial_3d_depth", spatial.optInt("spatial_3d_depth", 60))
            .putBoolean("spatial_surround", spatial.optBoolean("spatial_surround", false))
            .putInt("spatial_surround_intensity", spatial.optInt("spatial_surround_intensity", 50))
            .apply()
        changed = true
    }
    // Por último, e separado do `changed` de propósito: as preferências de som acima dizem
    // se é preciso reaplicar o audio na hora, e playlist/favorita não tem audio nenhum
    // para reaplicar. Envolver isto no `changed` faria o `refreshFx()` disparar à toa.
    val report = PlaylistBackup.import(
        applicationContext,
        PlaylistDb.get(applicationContext),
        root.optJSONObject("library")
    )
    return RestoreOutcome(settingsChanged = changed, report = report)
}

/**
 * Mostra o que voltou e, principalmente, o que não voltou.
 *
 * A tela antiga era um toast de "pronto" ou "falhou" — dois estados para um restore que
 * tem quatro resultados possíveis: voltou tudo, voltou parte, não voltou nada porque o app
 * não tem permissão de mídia, ou o arquivo nem era do Pulsa. Quem restaurou no celular
 * novo precisa saber a diferença entre "minha playlist veio pela metade" e "o backup está
 * estragado" — e essa diferença está no diálogo, não num toast que some em dois segundos.
 */
internal fun SettingsActivity.showRestoreReport(report: PlaylistBackup.Report) {
    val title: Int
    val body: String
    if (report.mediaPermissionMissing) {
        title = R.string.restore_needs_permission_title
        body = getString(R.string.restore_needs_permission_body)
    } else if (!report.changed && report.missing == 0) {
        title = R.string.restore_done
        body = getString(R.string.restore_empty)
    } else {
        title = if (report.changed) R.string.restore_done else R.string.restore_failed
        body = restoreSummary(report)
    }

    MaterialAlertDialogBuilder(this)
        .setTitle(title)
        .setMessage(body)
        .setPositiveButton(R.string.close, null)
        .show()
}

internal fun SettingsActivity.restoreSummary(r: PlaylistBackup.Report): String {
    val sb = StringBuilder()
    if (r.playlists > 0) sb.append("• ").append(getString(R.string.restore_n_playlists, r.playlists)).append('\n')
    if (r.songs > 0) sb.append("• ").append(getString(R.string.restore_n_songs, r.songs)).append('\n')
    if (r.favorites > 0) sb.append("• ").append(getString(R.string.restore_n_favorites, r.favorites)).append('\n')
    if (r.metaOverrides > 0) sb.append("• ").append(getString(R.string.restore_n_meta, r.metaOverrides)).append('\n')
    if (r.missing > 0) {
        sb.append('\n').append(getString(R.string.restore_missing_title, r.missing)).append('\n')
        r.missingExamples.forEach { sb.append("• ").append(it).append('\n') }
        sb.append(getString(R.string.restore_missing_hint))
    }
    return sb.toString().trim()
}

