package com.pulsa.player.util

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration

/** Deteccao de hardware: o mesmo APK roda em celular e Android TV. */
object UiMode {

    fun isTv(context: Context): Boolean {
        val manager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager ?: return false
        return manager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    }
}