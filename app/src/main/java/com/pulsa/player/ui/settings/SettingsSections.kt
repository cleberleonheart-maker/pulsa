package com.pulsa.player.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.audio.SilenceSkipper
import com.pulsa.player.core.Account
import com.pulsa.player.core.Changelog
import com.pulsa.player.core.Settings
import com.pulsa.player.playback.Playback
import com.pulsa.player.util.UpdateChecker

internal val SettingsActivity.self: SettingsActivity get() = this

/**
 * Seções da tela de Configurações.
 *
 * Cada função liga os controles de uma seção (`sect_*` de `activity_settings.xml`) às
 * preferências em [Settings]. Mantidas como extensões de [SettingsActivity] para que o
 * `onCreate` fique só com a orquestração.
 *
 * Documentação: CONFIGURACOES.md
 */

// ============================================================
// APARÊNCIA — fundo, estrelas, cor de destaque, idioma
// ============================================================

fun SettingsActivity.bindAppearance() {
    findViewById<MaterialSwitch>(R.id.bg_switch).apply {
        isChecked = Settings.animatedBg(this@bindAppearance)
        setOnCheckedChangeListener { _, checked ->
            Settings.setAnimatedBg(this@bindAppearance, checked)
        }
    }

    findViewById<MaterialSwitch>(R.id.stars_switch).apply {
        isChecked = Settings.starsOn(this@bindAppearance)
        setOnCheckedChangeListener { _, checked ->
            Settings.setStarsOn(this@bindAppearance, checked)
        }
    }

    findViewById<TextView>(R.id.accent_value)?.text = accentLabel()
    findViewById<View>(R.id.accent_row).setOnClickListener { pickAccent() }

    findViewById<TextView>(R.id.language_value)?.text = languageLabel()
    findViewById<View>(R.id.language_row).setOnClickListener { pickLanguage() }
}

// ============================================================
// ÁUDIO — qualidade, EQ, 8D, 3D/surround, crossfade
// ============================================================

fun SettingsActivity.bindAudio() {
    findViewById<TextView>(R.id.quality_value)?.text = qualityLabel()
    findViewById<View>(R.id.quality_row).setOnClickListener { pickQuality() }

    findViewById<MaterialSwitch>(R.id.eq_switch).apply {
        isChecked = Settings.equalizerOn(this@bindAudio)
        setOnCheckedChangeListener { _, checked ->
            Settings.setEqualizerOn(this@bindAudio, checked)
            Playback.refreshFx()
        }
    }

    findViewById<MaterialSwitch>(R.id.audio_8d_switch).apply {
        isChecked = Settings.audio8d(this@bindAudio)
        setOnCheckedChangeListener { _, checked ->
            Settings.setAudio8d(this@bindAudio, checked)
            Playback.refreshFx()
        }
    }

    // 3D e surround: os dois processam o mesmo par (L, R) dentro do `SpatialAudio`, então
    // cada um tem o seu botão e o seu ajuste de intensidade. Os sliders só aparecem com o
    // efeito ligado — slider de efeito desligado é ajuste de nada.
    findViewById<MaterialSwitch>(R.id.audio_3d_switch).apply {
        isChecked = Settings.spatial3d(this@bindAudio)
        setOnCheckedChangeListener { _, checked ->
            Settings.setSpatial3d(this@bindAudio, checked)
            showSpatialRows()
            Playback.refreshFx()
        }
    }

    findViewById<MaterialSwitch>(R.id.surround_switch).apply {
        isChecked = Settings.surround(this@bindAudio)
        setOnCheckedChangeListener { _, checked ->
            Settings.setSurround(this@bindAudio, checked)
            showSpatialRows()
            Playback.refreshFx()
        }
    }

    findViewById<SeekBar>(R.id.audio_3d_depth_seek).apply {
        progress = Settings.spatial3dDepth(this@bindAudio)
        findViewById<TextView>(R.id.audio_3d_depth_value)?.text =
            getString(R.string.spatial_level, progress)
        setOnSeekBarChangeListener(sliderPersist(
            R.id.audio_3d_depth_value,
            onChanged = { Settings.setSpatial3dDepth(this@bindAudio, it) },
            onDone = { Playback.refreshFx() }
        ))
    }

    findViewById<SeekBar>(R.id.surround_intensity_seek).apply {
        progress = Settings.surroundIntensity(this@bindAudio)
        findViewById<TextView>(R.id.surround_intensity_value)?.text =
            getString(R.string.spatial_level, progress)
        setOnSeekBarChangeListener(sliderPersist(
            R.id.surround_intensity_value,
            onChanged = { Settings.setSurroundIntensity(this@bindAudio, it) },
            onDone = { Playback.refreshFx() }
        ))
    }

    showSpatialRows()

    findViewById<TextView>(R.id.crossfade_value)?.text = crossfadeLabel()
    findViewById<View>(R.id.crossfade_row).setOnClickListener { pickCrossfade() }
}

// ============================================================
// SINCRONIZAÇÃO — ouvir juntos, controle remoto, handoff, servidor, backup
// ============================================================

fun SettingsActivity.bindSync() {
    findViewById<View>(R.id.mirror_row).setOnClickListener { mirrorMenu() }
    refreshMirrorLabel()
    findViewById<View>(R.id.remote_row).setOnClickListener { remoteControlDialog() }
    refreshRemoteLabel()

    findViewById<MaterialSwitch>(R.id.handoff_switch).apply {
        isChecked = Settings.handoffAutoAssume(this@bindSync)
        setOnCheckedChangeListener { _, checked ->
            Settings.setHandoffAutoAssume(this@bindSync, checked)
        }
    }

    findViewById<View>(R.id.server_row).setOnClickListener { serverDialog() }
    refreshServerLabel()
    findViewById<View>(R.id.server_token_row).setOnClickListener { promptServerToken() }
    refreshServerToken()

    findViewById<View>(R.id.backup_row).setOnClickListener {
        backupLauncher.launch("pulsa-backup.json")
    }
    findViewById<View>(R.id.restore_row).setOnClickListener {
        restoreLauncher.launch(arrayOf("application/json"))
    }
}

// ============================================================
// DJ / IA — locutora, resumo, gestos, mãos-livres, Gemini, reconhecimento
// ============================================================

fun SettingsActivity.bindDj() {
    findViewById<MaterialSwitch>(R.id.dj_radio_switch).apply {
        isChecked = Settings.djRadio(this@bindDj)
        setOnCheckedChangeListener { _, checked ->
            Settings.setDjRadio(this@bindDj, checked)
        }
    }

    findViewById<MaterialSwitch>(R.id.podcast_summary_switch).apply {
        isChecked = Settings.podcastSummary(this@bindDj)
        setOnCheckedChangeListener { _, checked ->
            Settings.setPodcastSummary(this@bindDj, checked)
        }
    }

    // Gestos (shake/inclinação): aqui só grava a escolha. Quem registra o sensor é a
    // MainActivity, no onResume, via `attachIfEnabled` — assim o callback do shake não é
    // sobrescrito por uma lambda vazia enquanto a tela de Ajustes está aberta.
    findViewById<MaterialSwitch>(R.id.gestures_switch).apply {
        isChecked = Settings.gesturesOn(this@bindDj)
        setOnCheckedChangeListener { _, checked ->
            Settings.setGesturesOn(this@bindDj, checked)
        }
    }

    findViewById<MaterialSwitch>(R.id.hotword_switch).apply {
        isChecked = Settings.hotword(this@bindDj)
        setOnCheckedChangeListener { _, checked ->
            if (checked) {
                val granted = ContextCompat.checkSelfPermission(
                    this@bindDj, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    hotwordLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    Settings.setHotword(this@bindDj, true)
                    com.pulsa.player.dj.Hotword.startIfNeeded(this@bindDj)
                }
            } else {
                Settings.setHotword(this@bindDj, false)
                com.pulsa.player.dj.Hotword.stopIfRunning(this@bindDj)
            }
        }
    }

    refreshRecToken()
    findViewById<View>(R.id.rec_token_row).setOnClickListener { promptRecToken() }
    findViewById<View>(R.id.gemini_row).setOnClickListener { promptGeminiKey() }
    refreshGeminiKey()
}

// ============================================================
// DOWNLOAD / DADOS / MODO KIDS
// ============================================================

fun SettingsActivity.bindDownloads() {
    findViewById<MaterialButton>(R.id.btn_download).setOnClickListener { startDownload() }

    findViewById<MaterialSwitch>(R.id.download_wifi_switch).apply {
        isChecked = Settings.downloadWifiOnly(this@bindDownloads)
        setOnCheckedChangeListener { _, checked ->
            Settings.setDownloadWifiOnly(this@bindDownloads, checked)
        }
    }

    findViewById<MaterialSwitch>(R.id.data_saver_switch).apply {
        isChecked = Settings.dataSaver(this@bindDownloads)
        setOnCheckedChangeListener { _, checked ->
            Settings.setDataSaver(this@bindDownloads, checked)
            // Vale na hora: o teto de resolução do vídeo que já está tocando muda agora,
            // sem esperar a próxima faixa nem uma troca de rede.
            Playback.refreshDataSaver()
        }
    }

    setupKidsMode()

    findViewById<MaterialSwitch>(R.id.podcast_prefetch_switch).apply {
        isChecked = Settings.podcastPrefetch(this@bindDownloads)
        setOnCheckedChangeListener { _, checked ->
            Settings.setPodcastPrefetch(this@bindDownloads, checked)
        }
    }

    findViewById<MaterialSwitch>(R.id.skip_silence_switch).apply {
        isChecked = Settings.skipSilence(this@bindDownloads)
        setOnCheckedChangeListener { _, checked ->
            Settings.setSkipSilence(this@bindDownloads, checked)
            // Vale na hora, sem esperar a próxima faixa: o processador lê a flag por buffer.
            SilenceSkipper.enabled = checked && Playback.currentSong?.isPodcast == true
        }
    }

    val limitValue = viewOrNull<TextView>(R.id.download_limit_value)
    refreshDownloadLimit(limitValue)
    findViewById<View>(R.id.download_limit_row).setOnClickListener {
        promptDownloadLimit(limitValue)
    }

    val cleanupValue = viewOrNull<TextView>(R.id.offline_cleanup_value)
    refreshOfflineCleanup(cleanupValue)
    findViewById<View>(R.id.offline_cleanup_row).setOnClickListener {
        promptOfflineCleanup(cleanupValue)
    }

    findViewById<View>(R.id.cover_download_row).setOnClickListener { startCoverDownload() }

    findViewById<MaterialButton>(R.id.btn_update_site).setOnClickListener {
        UpdateChecker.downloadFromSite(this@bindDownloads)
    }
}

// ============================================================
// MANUTENÇÃO — cache
// ============================================================

fun SettingsActivity.bindMaintenance() {
    val cacheValue = viewOrNull<TextView>(R.id.cache_value)
    updateCacheLabel(cacheValue)
    findViewById<View>(R.id.cache_row).setOnClickListener { clearCache(cacheValue) }
}

// ============================================================
// CONTA — ID, sair, versão e changelog
// ============================================================

fun SettingsActivity.bindAccount() {
    findViewById<View>(R.id.logout_row).setOnClickListener { confirmLogout() }

    viewOrNull<TextView>(R.id.account_id_value)?.text = Account.userId(this) ?: "—"

    viewOrNull<TextView>(R.id.settings_version)?.apply {
        text = getString(
            R.string.app_version,
            packageManager.getPackageInfo(packageName, 0).versionName
        )
        setOnClickListener { Changelog.show(this@bindAccount) }
    }
}
