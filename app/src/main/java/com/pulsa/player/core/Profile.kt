package com.pulsa.player.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream

object Profile {
    private const val FILE = "pulsa_profile"
    private const val MAX_DIM = 320

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun nick(context: Context): String =
        prefs(context).getString("nick", "") ?: ""

    fun setNick(context: Context, value: String) {
        prefs(context).edit().putString("nick", value.trim()).apply()
    }

    fun hasPhoto(context: Context): Boolean =
        prefs(context).getString("photo_b64", null) != null

    fun setPhoto(context: Context, bitmap: Bitmap) {
        val scaled = scaleDown(bitmap)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 90, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        prefs(context).edit().putString("photo_b64", b64).apply()
    }

    fun clearPhoto(context: Context) {
        prefs(context).edit().remove("photo_b64").apply()
    }

    fun getPhoto(context: Context): Bitmap? {
        val b64 = prefs(context).getString("photo_b64", null) ?: return null
        return runCatching {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val maxDim = MAX_DIM
        val maxOfBoth = maxOf(source.width, source.height)
        if (maxOfBoth <= maxDim) return source
        val scale = maxDim.toFloat() / maxOfBoth
        val newW = (source.width * scale).toInt().coerceAtLeast(1)
        val newH = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, newW, newH, true)
    }
}
