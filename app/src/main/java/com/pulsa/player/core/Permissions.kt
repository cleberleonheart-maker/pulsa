package com.pulsa.player.core

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    const val REQUEST_AUDIO = 100
    const val REQUEST_VIDEO = 101

    fun hasAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun request(activity: Activity) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
            permissions += Manifest.permission.POST_NOTIFICATIONS
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT < 29) {
                permissions += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        activity.requestPermissions(permissions.toTypedArray(), REQUEST_AUDIO)
    }

    fun hasVideo(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestVideo(activity: Activity) {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        activity.requestPermissions(permissions, REQUEST_VIDEO)
    }
}
