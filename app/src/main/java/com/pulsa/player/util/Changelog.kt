package com.pulsa.player.util

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.BuildConfig
import com.pulsa.player.R

object Changelog {

    private const val PREF = "pulsa_last_changelog"
    private const val KEY = "last_shown_code"
    private const val PREF_INSTALL = "pulsa_last_installed"
    private const val KEY_INSTALL = "last_installed_code"

    fun check(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY, 0L)
            if (last >= BuildConfig.VERSION_CODE.toLong()) return
            prefs.edit().putLong(KEY, BuildConfig.VERSION_CODE.toLong()).apply()
            show(context)
        } catch (t: Throwable) {
        }
    }

    fun checkUpdated(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREF_INSTALL, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_INSTALL, 0L)
            val current = BuildConfig.VERSION_CODE.toLong()
            if (last == 0L) {
                prefs.edit().putLong(KEY_INSTALL, current).apply()
                return
            }
            if (current > last) {
                prefs.edit().putLong(KEY_INSTALL, current).apply()
                showUpdated(context)
            }
        } catch (t: Throwable) {
        }
    }

    fun showUpdated(context: Context) {
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_installed)
            .setMessage(context.getString(
                R.string.update_installed_message,
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                } catch (t: Throwable) {
                    ""
                }
            ))
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun show(context: Context) {
        val changes = context.getString(R.string.changelog)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.changelog_title)
            .setMessage(changes)
            .setPositiveButton(R.string.close, null)
            .show()
    }
}