package com.pulsa.player

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.pulsa.player.core.Settings
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.sync.AdminApi
import com.pulsa.player.sync.AdminDevice
import com.pulsa.player.ui.AnimatedBackground

class BanActivity : AppCompatActivity() {

    private lateinit var loginPanel: View
    private lateinit var tokenInput: EditText
    private lateinit var loginBtn: View
    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var refreshBtn: View
    private lateinit var logoutBtn: View
    private var token: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ban)
        AnimatedBackground.apply(this)

        findViewById<ImageButton>(R.id.btn_ban_back).setOnClickListener { finish() }

        loginPanel = findViewById(R.id.ban_login_panel)
        tokenInput = findViewById(R.id.ban_token_input)
        loginBtn = findViewById(R.id.ban_login_btn)
        container = findViewById(R.id.ban_container)
        statusText = findViewById(R.id.ban_status)
        refreshBtn = findViewById(R.id.ban_refresh_btn)
        logoutBtn = findViewById(R.id.ban_logout_btn)

        loginBtn.setOnClickListener { doLogin() }
        refreshBtn.setOnClickListener { loadDevices() }
        logoutBtn.setOnClickListener {
            Settings.setAdminToken(this, "")
            showLogin()
        }

        val saved = Settings.adminToken(this)
        if (saved.isNotBlank()) {
            token = saved
            showPanel()
            loadDevices()
        } else {
            showLogin()
        }
    }

    private fun showLogin() {
        token = ""
        loginPanel.visibility = View.VISIBLE
        refreshBtn.visibility = View.GONE
        logoutBtn.visibility = View.GONE
        container.removeAllViews()
        statusText.text = ""
    }

    private fun showPanel() {
        loginPanel.visibility = View.GONE
        refreshBtn.visibility = View.VISIBLE
        logoutBtn.visibility = View.VISIBLE
    }

    private fun doLogin() {
        val value = tokenInput.text.toString().trim()
        if (value.isEmpty()) {
            Toast.makeText(this, R.string.ban_token_empty, Toast.LENGTH_SHORT).show()
            return
        }
        tokenInput.isEnabled = false
        loginBtn.isEnabled = false
        statusText.text = getString(R.string.ban_login_progress)
        ThreadPool.post {
            val ok = AdminApi.login(this, value)
            ThreadPool.onUi {
                tokenInput.isEnabled = true
                loginBtn.isEnabled = true
                if (isFinishing || isDestroyed) return@onUi
                if (ok) {
                    token = value
                    Settings.setAdminToken(this, value)
                    showPanel()
                    loadDevices()
                } else {
                    statusText.setText(R.string.ban_login_fail)
                }
            }
        }
    }

    private fun loadDevices() {
        if (token.isBlank()) return
        statusText.setText(R.string.ban_loading)
        ThreadPool.post {
            val list = AdminApi.devices(applicationContext, token)
            ThreadPool.onUi {
                if (isFinishing || isDestroyed) return@onUi
                render(list)
            }
        }
    }

    private fun render(list: List<AdminDevice>?) {
        container.removeAllViews()
        if (list == null) {
            statusText.setText(R.string.ban_error)
            return
        }
        val banned = list.count { it.banned }
        statusText.text = getString(R.string.ban_summary, list.size, banned)
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.gravity = Gravity.CENTER
            empty.setPadding(0, dp(28), 0, 0)
            empty.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            empty.text = getString(R.string.ban_empty)
            container.addView(empty)
            return
        }
        for (d in list) container.addView(row(d))
    }

    private fun row(d: AdminDevice): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = ContextCompat.getDrawable(this, R.drawable.bg_vcard)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(10)
        row.layoutParams = lp
        row.setPadding(dp(14), dp(12), dp(12), dp(12))
        row.gravity = Gravity.CENTER_VERTICAL

        val info = LinearLayout(this)
        info.orientation = LinearLayout.VERTICAL
        info.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        info.setPadding(0, 0, dp(8), 0)

        val id = TextView(this)
        id.text = d.id
        id.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        id.textSize = 13f
        id.setTypeface(null, Typeface.BOLD)
        info.addView(id)

        val meta = mutableListOf<String>()
        if (d.events > 0) meta.add(getString(R.string.ban_events, d.events))
        if (d.version.isNotBlank()) meta.add(getString(R.string.ban_version, d.version))
        if (d.last.isNotBlank()) meta.add(getString(R.string.ban_last, d.last))
        if (meta.isNotEmpty()) {
            val m = TextView(this)
            m.text = meta.joinToString(" · ")
            m.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            m.textSize = 11f
            info.addView(m)
        }
        row.addView(info)

        val btn = TextView(this)
        btn.text = getString(if (d.banned) R.string.ban_unban else R.string.ban_ban)
        btn.background = ContextCompat.getDrawable(
            this,
            if (d.banned) R.drawable.bg_pill_cyan else R.drawable.bg_pill_red
        )
        btn.setTextColor(
            ContextCompat.getColor(
                this, if (d.banned) R.color.on_primary else android.R.color.white
            )
        )
        btn.textSize = 13f
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(16), dp(9), dp(16), dp(9))
        btn.setOnClickListener { confirmBan(d) }
        row.addView(btn)

        return row
    }

    private fun confirmBan(d: AdminDevice) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ban_title)
            .setMessage(
                getString(
                    if (d.banned) R.string.ban_unban_confirm else R.string.ban_ban_confirm,
                    d.id
                )
            )
            .setPositiveButton(
                getString(if (d.banned) R.string.ban_unban else R.string.ban_ban)
            ) { _, _ ->
                ThreadPool.post {
                    val ok = AdminApi.setBan(applicationContext, token, d.id, !d.banned)
                    ThreadPool.onUi {
                        if (isFinishing || isDestroyed) return@onUi
                        Toast.makeText(
                            this,
                            if (ok) R.string.ban_updated else R.string.ban_error,
                            Toast.LENGTH_LONG
                        ).show()
                        if (ok) loadDevices()
                    }
                }
            }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}