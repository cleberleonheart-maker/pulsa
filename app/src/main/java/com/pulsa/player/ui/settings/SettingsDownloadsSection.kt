package com.pulsa.player.ui.settings

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Helper
import com.pulsa.player.core.KidsMode
import com.pulsa.player.core.Settings
import com.pulsa.player.media.OfficialMediaResolver
import com.pulsa.player.media.UrlDownloader
import com.pulsa.player.work.PulsaWork

/**
 * Downloads, dados e modo kids — download por URL, limites, capas e PIN.
 */

internal fun SettingsActivity.startDownload() {
    val url = findViewById<EditText>(R.id.download_url).text.toString().trim()
    val name = findViewById<EditText>(R.id.download_name).text.toString().trim()
    // #277 – a resolução vai para a rede em background: no main thread o
    // HttpURLConnection lançaria NetworkOnMainThreadException e o link era
    // tratado como página comum.
    OfficialMediaResolver.resolveAsync(url) { resolved ->
        val targetUrl = resolved?.directUrl ?: url
        if (resolved == null) {
            val errorRes = UrlDownloader.validate(url)
            if (errorRes != null) {
                Toast.makeText(
                    this, errorRes,
                    if (errorRes == R.string.download_direct_only) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
                ).show()
            } else {
                enqueueDownload(targetUrl, name)
            }
        } else {
            // O nome sugerido pelo resolver (artista - faixa) é melhor que o da URL.
            enqueueDownload(targetUrl, name.ifEmpty { resolved.name.orEmpty() })
        }
    }
}

private fun SettingsActivity.enqueueDownload(targetUrl: String, name: String) {
    val finalName = name.ifEmpty { UrlDownloader.guessName(targetUrl) }
    // Virou job do WorkManager: espera a rede, e se a tela fechar no meio o download
    // continua -- antes era uma thread do pool que morria junto com o processo sem aviso.
    val job = PulsaWork.download(this, targetUrl, finalName)
    if (job == null) {
        Toast.makeText(
            this, getString(R.string.download_failed, finalName), Toast.LENGTH_SHORT
        ).show()
        return
    }
    Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
    // O callback pode chegar depois desta tela fechar; o Toast usa o contexto de aplicacao
    // para nao prender uma Activity destruida.
    val app = applicationContext
    PulsaWork.watchDownload(this, job) { ok, _ ->
        val message = if (ok) {
            app.getString(R.string.download_done, finalName)
        } else {
            app.getString(R.string.download_failed, finalName)
        }
        Toast.makeText(app, message, Toast.LENGTH_SHORT).show()
    }
}

internal fun SettingsActivity.refreshDownloadLimit(valueView: TextView?) {
    val mb = Settings.downloadLimitMb(this)
    valueView?.text = if (mb <= 0) {
        getString(R.string.downloads_limit_value_none)
    } else {
        Helper.formatBytes(mb * 1024L * 1024L)
    }
}

/**
 * Item 293 — modo família/kids. O interruptor é protegido por senha: ligar cria uma senha
 * (se ainda não houver), desligar exige a senha. A triagem das faixas está em `KidsFilter`
 * e o teto de volume em `KidsMode`.
 */
internal fun SettingsActivity.setupKidsMode() {
    val kidsSwitch = findViewById<MaterialSwitch>(R.id.kids_mode_switch)
    val pinRow = findViewById<View>(R.id.kids_pin_row)
    val pinButton = findViewById<MaterialButton>(R.id.kids_pin_button)

    fun refreshPinRow() {
        pinRow.visibility =
            if (Settings.kidsPin(self).isNotBlank()) View.VISIBLE else View.GONE
    }

    kidsSwitch.isChecked = Settings.kidsMode(this)
    refreshPinRow()

    kidsSwitch.setOnCheckedChangeListener { _, checked ->
        if (checked) {
            if (Settings.kidsPin(self).isBlank()) {
                promptNewPin { hash ->
                    Settings.setKidsPin(self, hash)
                    Settings.setKidsMode(self, true)
                    KidsMode.clampVolume(self)
                    refreshPinRow()
                }
            } else {
                Settings.setKidsMode(self, true)
                KidsMode.clampVolume(self)
            }
        } else {
            val stored = Settings.kidsPin(self)
            if (stored.isBlank()) {
                Settings.setKidsMode(self, false)
            } else {
                promptPin(R.string.kids_pin_ask_off) { pin ->
                    if (pin != null && KidsMode.matches(pin, stored)) {
                        Settings.setKidsMode(self, false)
                    } else {
                        if (pin != null) {
                            Toast.makeText(
                                self, R.string.kids_pin_wrong, Toast.LENGTH_SHORT
                            ).show()
                        }
                        // Cancelou ou errou: o modo continua ligado, então o interruptor volta.
                        kidsSwitch.isChecked = true
                    }
                }
            }
        }
    }

    pinButton.setOnClickListener {
        val stored = Settings.kidsPin(self)
        promptPin(R.string.kids_pin_ask_change) { pin ->
            if (pin != null && KidsMode.matches(pin, stored)) {
                promptNewPin { hash ->
                    Settings.setKidsPin(self, hash)
                    Toast.makeText(
                        self, R.string.kids_pin_saved, Toast.LENGTH_SHORT
                    ).show()
                    refreshPinRow()
                }
            } else if (pin != null) {
                Toast.makeText(
                    self, R.string.kids_pin_wrong, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

/** Diálogo de senha: devolve o texto digitado, ou `null` se cancelar. */
internal fun SettingsActivity.promptPin(titleRes: Int, onResult: (String?) -> Unit) {
    val input = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    MaterialAlertDialogBuilder(this)
        .setTitle(titleRes)
        .setView(input)
        .setPositiveButton(R.string.save) { _, _ -> onResult(input.text.toString()) }
        .setNegativeButton(R.string.cancel) { _, _ -> onResult(null) }
        .setOnCancelListener { onResult(null) }
        .show()
}

/** Diálogo de nova senha, com confirmação. Só chama [onOk] quando as duas batem. */
internal fun SettingsActivity.promptNewPin(onOk: (String) -> Unit) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
    }
    fun field(hint: Int) = EditText(this).apply {
        inputType = android.text.InputType.TYPE_CLASS_NUMBER or
            android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        this.hint = getString(hint)
    }
    val first = field(R.string.kids_pin_new)
    val second = field(R.string.kids_pin_confirm)
    box.addView(first)
    box.addView(second)
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.kids_pin_create)
        .setView(box)
        .setPositiveButton(R.string.save) { _, _ ->
            val a = first.text.toString()
            val b = second.text.toString()
            when {
                a.isBlank() ->
                    Toast.makeText(this, R.string.kids_pin_empty, Toast.LENGTH_SHORT).show()
                a != b ->
                    Toast.makeText(this, R.string.kids_pin_mismatch, Toast.LENGTH_SHORT).show()
                else -> onOk(KidsMode.hash(a))
            }
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.promptDownloadLimit(valueView: TextView?) {
    val current = Settings.downloadLimitMb(this)
    val options = intArrayOf(0, 1024, 2048, 5120)
    val labels = options.map { mb ->
        if (mb <= 0) getString(R.string.downloads_limit_value_none)
        else Helper.formatBytes(mb * 1024L * 1024L)
    }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.downloads_limit)
        .setSingleChoiceItems(labels, options.indexOfFirst { it == current }.let { if (it < 0) 0 else it }) { _, which ->
            Settings.setDownloadLimitMb(this, options[which])
            refreshDownloadLimit(valueView)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.refreshOfflineCleanup(valueView: TextView?) {
    val days = Settings.offlineCleanupDays(this)
    valueView?.text = if (days <= 0) {
        getString(R.string.offline_cleanup_value_off)
    } else {
        getString(R.string.offline_cleanup_days, days)
    }
}

internal fun SettingsActivity.promptOfflineCleanup(valueView: TextView?) {
    val current = Settings.offlineCleanupDays(this)
    val options = intArrayOf(0, 7, 30, 60)
    val labels = options.map { days ->
        if (days <= 0) getString(R.string.offline_cleanup_value_off)
        else getString(R.string.offline_cleanup_days, days)
    }.toTypedArray()
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.offline_cleanup)
        .setSingleChoiceItems(labels, options.indexOfFirst { it == current }.let { if (it < 0) 0 else it }) { _, which ->
            Settings.setOfflineCleanupDays(this, options[which])
            refreshOfflineCleanup(valueView)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.startCoverDownload() {
    val id = PulsaWork.downloadCovers(this)
    if (id == null) {
        Toast.makeText(this, R.string.covers_failed, Toast.LENGTH_SHORT).show()
        return
    }
    Toast.makeText(this, R.string.covers_started, Toast.LENGTH_SHORT).show()
    // O contexto é o de aplicação: o job termina quando o usuário já pode ter saído da tela.
    PulsaWork.watchCovers(applicationContext, id) { count ->
        val msg = if (count > 0) getString(R.string.covers_done, count) else getString(R.string.covers_none)
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }
}

