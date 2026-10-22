package com.pulsa.player.ui.settings

import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.LoginActivity
import com.pulsa.player.R
import com.pulsa.player.SettingsActivity
import com.pulsa.player.core.Account
import com.pulsa.player.core.Helper
import com.pulsa.player.core.Settings

/**
 * Manutenção e conta — cache e logout.
 */

internal fun SettingsActivity.confirmLogout() {
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.logout)
        .setMessage(R.string.logout_confirm)
        .setPositiveButton(R.string.logout) { d, _ ->
            d.dismiss()
            Account.setLoggedIn(this, false)
            startActivity(
                Intent(this, LoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
            finish()
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

internal fun SettingsActivity.updateCacheLabel(view: TextView?) {
    view?.text = getString(R.string.cache_size, Helper.formatBytes(Settings.cacheSize(this)))
}

internal fun SettingsActivity.clearCache(valueView: TextView?) {
    val size = Settings.cacheSize(this)
    MaterialAlertDialogBuilder(this)
        .setTitle(R.string.cache_title)
        .setMessage(getString(R.string.cache_confirm, Helper.formatBytes(size)))
        .setPositiveButton(R.string.delete) { d, _ ->
            d.dismiss()
            val freed = Settings.clearCache(this)
            Toast.makeText(
                this,
                getString(R.string.cache_cleared, Helper.formatBytes(freed)),
                Toast.LENGTH_SHORT
            ).show()
            updateCacheLabel(valueView)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

