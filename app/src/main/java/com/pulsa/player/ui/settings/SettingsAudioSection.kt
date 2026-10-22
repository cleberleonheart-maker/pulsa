package com.pulsa.player.ui.settings

import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Settings
import com.pulsa.player.playback.Playback

/**
 * Áudio — rótulo de qualidade, seletor de preset, slider espacial.
 */

internal fun SettingsActivity.qualityLabel(): String {
    return getString(Settings.qualityLabelRes(Settings.audioQuality(this)))
}

/**
 * Slider de intensidade: grava na hora, mas só avisa o playback no fim do arrasto.
 *
 * O `SpatialAudio` lê os parâmetros a cada buffer, então o efeito acompanha o dedo sem
 * precisar de nada do playback; o que precisa é persistir a escolha e reler os ajustes
 * quando o dedo sai. Gravar a cada pixel seria uma escrita por frame do dedo.
 */
internal fun SettingsActivity.sliderPersist(
    labelId: Int,
    onChanged: (Int) -> Unit,
    onDone: () -> Unit
): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
    private val label: TextView? by lazy { viewOrNull<TextView>(labelId) }

    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
        if (!fromUser) return
        onChanged(progress)
        label?.text = getString(R.string.spatial_level, progress)
    }

    override fun onStartTrackingTouch(seekBar: SeekBar?) {}

    override fun onStopTrackingTouch(seekBar: SeekBar?) = onDone()
}

/** Esconde o slider de cada efeito desligado. */
internal fun SettingsActivity.showSpatialRows() {
    findViewById<View>(R.id.audio_3d_depth_row).visibility =
        if (Settings.spatial3d(this)) View.VISIBLE else View.GONE
    findViewById<View>(R.id.surround_intensity_row).visibility =
        if (Settings.surround(this)) View.VISIBLE else View.GONE
}

internal fun SettingsActivity.pickQuality() {
    val keys = arrayOf(
        Settings.QUALITY_AUTO,
        Settings.QUALITY_DEFAULT,
        Settings.QUALITY_BASS,
        Settings.QUALITY_VOICES,
        Settings.QUALITY_PODCAST,
        Settings.QUALITY_TREBLE,
        Settings.QUALITY_ROCK,
        Settings.QUALITY_DANCE,
        Settings.QUALITY_POP,
        Settings.QUALITY_JAZZ,
        Settings.QUALITY_ACOUSTIC,
        Settings.QUALITY_CLASSICAL,
        Settings.QUALITY_LOUDNESS
    )
    val names = keys.map { getString(Settings.qualityLabelRes(it)) }.toTypedArray()
    val current = keys.indexOf(Settings.audioQuality(this))
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.pick_quality)
        .setSingleChoiceItems(names, current) { dialog, which ->
            Settings.setAudioQuality(this, keys[which])
            Playback.refreshFx()
            Playback.reapplyDanceParams()
            findViewById<TextView>(R.id.quality_value)?.text = qualityLabel()
            dialog.dismiss()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

