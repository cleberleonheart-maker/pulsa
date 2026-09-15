package com.pulsa.player

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.pulsa.player.data.Library
import com.pulsa.player.util.AnimatedBackground
import com.pulsa.player.util.Changelog
import com.pulsa.player.util.DjLearn
import com.pulsa.player.util.Helper
import com.pulsa.player.util.Profile
import com.pulsa.player.util.Settings

class PerfilActivity : AppCompatActivity() {

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) applyPhoto(uri)
        }

    private val accentOrder = arrayOf(
        Settings.ACCENT_PURPLE,
        Settings.ACCENT_BLUE,
        Settings.ACCENT_GREEN,
        Settings.ACCENT_AMBER,
        Settings.ACCENT_RED,
        Settings.ACCENT_PINK,
        Settings.ACCENT_TEAL
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_perfil)

        AnimatedBackground.apply(this)
        findViewById<View>(R.id.btn_perfil_back).setOnClickListener { finish() }

        val avatar = findViewById<ShapeableImageView>(R.id.profile_avatar)
        renderPhoto(avatar)
        avatar.setOnClickListener { pickPhoto.launch("image/*") }
        avatar.setOnLongClickListener {
            if (Profile.hasPhoto(this)) {
                confirmRemovePhoto(avatar)
            }
            true
        }

        val nick = findViewById<EditText>(R.id.profile_nick)
        nick.setText(Profile.nick(this))
        nick.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveNick(nick)
        }

        findViewById<MaterialSwitch>(R.id.dj_radio_switch).apply {
            isChecked = Settings.djRadio(this@PerfilActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setDjRadio(this@PerfilActivity, checked)
            }
        }
        findViewById<MaterialSwitch>(R.id.dj_voice_switch).apply {
            isChecked = Settings.djVoice(this@PerfilActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setDjVoice(this@PerfilActivity, checked)
            }
        }

        findViewById<View>(R.id.stats_row).setOnClickListener { showStats() }

        val cacheValue = findViewById<TextView>(R.id.cache_value)
        updateCacheLabel(cacheValue)
        findViewById<View>(R.id.cache_row).setOnClickListener { clearCache(cacheValue) }

        findViewById<View>(R.id.changelog_row).setOnClickListener { Changelog.show(this) }

        findViewById<View>(R.id.full_settings_row).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<TextView>(R.id.perfil_version).apply {
            text = getString(
                R.string.app_version,
                runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
                    .getOrDefault("?")
            )
        }

        setupAccentSwatches()
        findAccentValue().text = getString(Settings.accentLabelRes(Settings.accent(this)))
    }

    override fun onStart() {
        super.onStart()
        AnimatedBackground.apply(this)
    }

    override fun onStop() {
        AnimatedBackground.stop()
        super.onStop()
    }

    override fun onPause() {
        saveNick(findViewById(R.id.profile_nick))
        super.onPause()
    }

    private fun saveNick(input: EditText) {
        Profile.setNick(this, input.text.toString())
    }

    private fun renderPhoto(target: ShapeableImageView?) {
        val t = target ?: return
        val photo = Profile.getPhoto(this)
        if (photo != null) {
            t.setImageBitmap(photo)
            t.clearColorFilter()
        } else {
            t.setImageResource(R.drawable.ic_person)
        }
    }

    private fun applyPhoto(uri: Uri) {
        val bmp = decodeSampled(uri, 600, 600)
        if (bmp == null) {
            Toast.makeText(this, R.string.photo_failed, Toast.LENGTH_SHORT).show()
            return
        }
        Profile.setPhoto(this, bmp)
        renderPhoto(findViewById(R.id.profile_avatar))
        Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmRemovePhoto(avatar: ShapeableImageView) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_photo)
            .setMessage(R.string.remove_photo_confirm)
            .setPositiveButton(R.string.remove_photo) { d, _ ->
                d.dismiss()
                Profile.clearPhoto(this)
                renderPhoto(avatar)
                Toast.makeText(this, R.string.profile_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun decodeSampled(uri: Uri, maxW: Int, maxH: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / sample > maxW * 2 || bounds.outHeight / sample > maxH * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    private fun findAccentValue(): TextView = findViewById(R.id.accent_value)

    private fun setupAccentSwatches() {
        val row = findViewById<LinearLayout>(R.id.accent_row)
        val current = Settings.accent(this)
        accentOrder.forEach { key ->
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                    marginStart = dp(8)
                    marginEnd = dp(8)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(swatchColor(key))
                    setStroke(dp(3), if (key == current) 0xFFFFFFFF.toInt() else 0x22FFFFFF)
                }
                alpha = if (key == current) 1f else 0.75f
                isClickable = true
                contentDescription = getString(Settings.accentLabelRes(key))
                setOnClickListener {
                    val currentKey = Settings.accent(this@PerfilActivity)
                    if (key == currentKey) return@setOnClickListener
                    Settings.setAccent(this@PerfilActivity, key)
                    recreate()
                }
            }
            row.addView(dot)
        }
    }

    private fun swatchColor(key: String): Int = when (key) {
        Settings.ACCENT_BLUE -> ContextCompat.getColor(this, R.color.primary_blue)
        Settings.ACCENT_GREEN -> ContextCompat.getColor(this, R.color.primary_green)
        Settings.ACCENT_AMBER -> ContextCompat.getColor(this, R.color.primary_amber)
        Settings.ACCENT_RED -> ContextCompat.getColor(this, R.color.primary_red)
        Settings.ACCENT_PINK -> ContextCompat.getColor(this, R.color.primary_pink)
        Settings.ACCENT_TEAL -> ContextCompat.getColor(this, R.color.primary_teal)
        else -> ContextCompat.getColor(this, R.color.primary)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showStats() {
        val songsById = Library.allSongs(this).associateBy { it.id }
        val top = DjLearn.topSongs(this, 30, 10)
        val total = DjLearn.topSongs(this, 30, 10000).sumOf { it.second }
        val lines = if (top.isEmpty()) {
            listOf(getString(R.string.stats_empty))
        } else {
            top.mapNotNull { (id, count) ->
                val song = songsById[id] ?: return@mapNotNull null
                getString(
                    R.string.stats_item,
                    "${song.title} · ${song.artist}",
                    count
                )
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.stats_title) + "  ·  " + getString(R.string.stats_recent, total))
            .setMessage(lines.joinToString("\n"))
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun updateCacheLabel(view: TextView) {
        view.text = getString(R.string.cache_size, Helper.formatBytes(Settings.cacheSize(this)))
    }

    private fun clearCache(valueView: TextView) {
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
}