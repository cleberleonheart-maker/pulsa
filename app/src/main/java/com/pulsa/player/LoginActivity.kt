package com.pulsa.player

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.SignInButton
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.pulsa.player.core.Account
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.sync.ConfirmMail
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

class LoginActivity : AppCompatActivity() {

    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var identifierInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var confirmInput: EditText
    private lateinit var error: TextView
    private lateinit var confirmWrap: View
    private lateinit var primaryButton: MaterialButton
    private lateinit var forgot: TextView
    private var createMode = false
    private var ready = false
    private val orbSets = mutableListOf<AnimatorSet>()
    private var pulseSet: AnimatorSet? = null
    private var googleClient: GoogleSignInClient? = null
    private lateinit var googleLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        Telemetry.log(this, "LOGIN onCreate")
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        AnimatedBackground.apply(this)

        registerGoogleLauncher()
        setupGoogleSignIn()

        title = findViewById(R.id.login_title)
        subtitle = findViewById(R.id.login_subtitle)
        identifierInput = findViewById(R.id.login_identifier)
        passwordInput = findViewById(R.id.login_password)
        confirmInput = findViewById(R.id.login_confirm)
        confirmWrap = findViewById(R.id.login_confirm_wrap)
        error = findViewById(R.id.login_error)
        primaryButton = findViewById(R.id.btn_login_primary)
        forgot = findViewById(R.id.btn_login_forgot)

        findViewById<TextView>(R.id.login_version).text =
            getString(R.string.app_version, packageManager.getPackageInfo(packageName, 0).versionName)

        checkSmtpStatus()

        val tabs = findViewById<TabLayout>(R.id.login_tabs)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                createMode = tab.position == 1
                renderMode()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })
        (if (Account.hasAccount(this)) 0 else 1).let { tabs.getTabAt(it)?.select() ?: renderMode() }
        ready = true

        primaryButton.setOnClickListener { submit() }
        forgot.setOnClickListener { confirmReset() }
        findViewById<TextView>(R.id.btn_login_guest).setOnClickListener { enterGuest() }

        animateEntrance()
    }

    private fun animateEntrance() {
        val logo = findViewById<View>(R.id.login_logo)
        val brand = findViewById<View>(R.id.login_brand)
        val tagline = findViewById<View>(R.id.login_tagline)
        val card = findViewById<View>(R.id.login_card)
        val version = findViewById<View>(R.id.login_version)

        floatOrb(R.id.login_orb_purple, 0, 26f, 7000L)
        floatOrb(R.id.login_orb_blue, 1, 34f, 9200L)
        floatOrb(R.id.login_orb_pink, 2, 30f, 8200L)
        pulseGlow()

        logo.apply {
            alpha = 0f
            scaleX = 0.7f
            scaleY = 0.7f
            animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(520).setInterpolator(DecelerateInterpolator(1.5f))
                .start()
        }
        brand.apply {
            alpha = 0f
            translationY = 18f
            animate().alpha(1f).translationY(0f)
                .setDuration(380).setStartDelay(90).setInterpolator(DecelerateInterpolator())
                .start()
        }
        tagline.apply {
            alpha = 0f
            translationY = 14f
            animate().alpha(1f).translationY(0f)
                .setDuration(360).setStartDelay(150).setInterpolator(DecelerateInterpolator())
                .start()
        }
        card.apply {
            alpha = 0f
            translationY = 36f
            animate().alpha(1f).translationY(0f)
                .setDuration(460).setStartDelay(160).setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
        version.apply {
            alpha = 0f
            animate().alpha(1f)
                .setDuration(400).setStartDelay(420).setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    private fun floatOrb(id: Int, phase: Int, drift: Float, duration: Long) {
        val orb = findViewById<View>(id) ?: return
        val startY = orb.translationY
        val set = AnimatorSet()
        set.playSequentially(
            ObjectAnimator.ofFloat(orb, View.TRANSLATION_Y, startY, startY + drift).setDuration(duration),
            ObjectAnimator.ofFloat(orb, View.TRANSLATION_Y, startY + drift, startY - drift).setDuration(duration * 2),
            ObjectAnimator.ofFloat(orb, View.TRANSLATION_Y, startY - drift, startY).setDuration(duration)
        )
        set.interpolator = DecelerateInterpolator(0.8f)
        set.startDelay = phase * 600L
        orbSets.add(set)
        set.start()
    }

    private fun pulseGlow() {
        val glow = findViewById<View>(R.id.login_logo_glow) ?: return
        val set = AnimatorSet()
        set.playTogether(
            ObjectAnimator.ofFloat(glow, View.SCALE_X, 0.9f, 1.15f),
            ObjectAnimator.ofFloat(glow, View.SCALE_Y, 0.9f, 1.15f),
            ObjectAnimator.ofFloat(glow, View.ALPHA, 0.75f, 1f)
        )
        set.duration = 1800
        set.interpolator = DecelerateInterpolator()
        pulseSet = set
        set.start()
    }

    override fun onStart() {
        super.onStart()
        AnimatedBackground.apply(this)
    }

    private fun registerGoogleLauncher() {
        googleLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                handleGoogleResult(data)
            } else {
                Telemetry.log(this, "LOGIN google cancelado")
            }
        }
    }

    private fun setupGoogleSignIn() {
        val btn = findViewById<SignInButton>(R.id.btn_login_google)
        val divider = findViewById<View>(R.id.login_google_or)
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isEmpty()) {
            btn.visibility = View.GONE
            btn.setOnClickListener(null)
            divider.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        btn.setSize(SignInButton.SIZE_WIDE)
        btn.setOnClickListener {
            val playOk = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this@LoginActivity) == ConnectionResult.SUCCESS
            if (!playOk) {
                Toast.makeText(this, R.string.login_google_gms, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            startGoogleSignIn()
        }
    }

    private fun startGoogleSignIn() {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        if (clientId.isEmpty()) {
            Toast.makeText(this, R.string.login_google_not_configured, Toast.LENGTH_SHORT).show()
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(clientId)
            .build()
        val client = GoogleSignIn.getClient(this, options)
        googleClient = client
        client.signOut().addOnCompleteListener {
            runCatching {
                googleLauncher.launch(client.signInIntent)
            }.onFailure { t ->
                Telemetry.log(this, "LOGIN google launch falhou: ${t.message}")
                Toast.makeText(this, R.string.login_google_gms, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleGoogleResult(data: Intent) {
        val account = try {
            GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
        } catch (e: ApiException) {
            Telemetry.log(this, "LOGIN google falhou ${e.statusCode}: ${e.message}")
            showError(getString(R.string.login_google_error))
            return
        }
        val idToken = account.idToken
        if (idToken.isNullOrEmpty()) {
            showError(getString(R.string.login_google_error))
            return
        }
        verifyGoogleToken(idToken)
    }

    private fun verifyGoogleToken(idToken: String) {
        val clientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        val main = Handler(Looper.getMainLooper())
        ThreadPool.post {
            val email = try {
                val url = "https://oauth2.googleapis.com/tokeninfo?id_token=" +
                    URLEncoder.encode(idToken, "UTF-8")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                conn.inputStream.close()
                val json = JSONObject(text)
                val aud = json.optString("aud", "")
                val verified = json.optString("email_verified", "false")
                if (aud != clientId || !verified.equals("true", true)) {
                    Telemetry.log(this@LoginActivity, "LOGIN google token invalido aud=$aud verified=$verified")
                    main.post { showError(getString(R.string.login_google_error)) }
                    return@post
                }
                json.optString("email", "")
            } catch (t: Throwable) {
                Telemetry.log(this@LoginActivity, "LOGIN google verificacao falhou: ${t.message}")
                null
            }
            if (email.isNullOrEmpty()) {
                main.post { showError(getString(R.string.login_google_error)) }
                return@post
            }
            main.post { loginWithGoogle(email) }
        }
    }

    private fun loginWithGoogle(email: String) {
        val existing = Account.identifier(this)
        if (existing == null || !existing.equals(email, ignoreCase = true)) {
            val randomPw = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            Account.create(this, email, randomPw)
        }
        Account.setProvider(this, "google")
        Account.enterAccount(this)
        Telemetry.log(this, "LOGIN google sucesso $email")
        Toast.makeText(this, R.string.login_google_success, Toast.LENGTH_SHORT).show()
        enterMain()
    }

    override fun onStop() {
        orbSets.forEach { runCatching { it.cancel() } }
        orbSets.clear()
        runCatching { pulseSet?.cancel() }
        AnimatedBackground.stop()
        super.onStop()
    }

    private fun renderMode() {
        if (createMode) {
            title.text = getString(R.string.login_create_title)
            subtitle.text = getString(R.string.login_subtitle_create)
            primaryButton.text = getString(R.string.login_button_create)
            forgot.visibility = View.GONE
            setVisible(confirmWrap, true)
        } else {
            title.text = getString(R.string.login_title)
            subtitle.text = getString(R.string.login_subtitle_login)
            primaryButton.text = getString(R.string.login_button)
            forgot.visibility = View.VISIBLE
            setVisible(confirmWrap, false)
        }
        fadeIn(title)
        fadeIn(subtitle)
        if (ready) fadeIn(forgot)
        error.visibility = View.GONE
    }

    private fun setVisible(view: View, show: Boolean) {
        view.clearAnimation()
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.translationY = -14f
            view.animate().alpha(1f).translationY(0f)
                .setDuration(240).setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            view.animate().alpha(0f).translationY(16f)
                .setDuration(180).withEndAction { view.visibility = View.GONE }
                .start()
        }
    }

    private fun fadeIn(view: View) {
        view.clearAnimation()
        view.alpha = 0f
        view.animate().alpha(1f)
            .setDuration(260).setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun submit() {
        val identifier = identifierInput.text.toString().trim()
        val password = passwordInput.text.toString()
        if (!Account.isIdentifierValid(identifier)) {
            showError(getString(R.string.login_invalid_identifier))
            return
        }
        if (password.length < 4) {
            showError(getString(R.string.login_password_short))
            return
        }
        if (createMode) {
            val confirm = confirmInput.text.toString()
            if (confirm != password) {
                showError(getString(R.string.login_password_mismatch))
                return
            }
            Account.create(this, identifier, password)
            Account.enterAccount(this)
            Toast.makeText(this, R.string.login_created_toast, Toast.LENGTH_SHORT).show()
            sendWelcome(identifier)
            enterMain()
        } else {
            if (Account.verify(this, identifier, password)) {
                Account.enterAccount(this)
                enterMain()
            } else {
                showError(getString(R.string.login_wrong))
            }
        }
    }

    private fun showError(message: String) {
        error.clearAnimation()
        error.text = message
        error.visibility = View.VISIBLE
        error.alpha = 0f
        error.translationY = 10f
        error.animate().alpha(1f).translationY(0f)
            .setDuration(240).setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun checkSmtpStatus() {
        val tv = findViewById<TextView>(R.id.login_smtp_status) ?: return
        ConfirmMail.smtpStatus(this) { status ->
            if (isFinishing || isDestroyed) return@smtpStatus
            val res = when {
                status.isBlank() || status == "?" -> R.string.smtp_status_unknown
                status.startsWith("online", true) || status.startsWith("ok", true) ->
                    R.string.smtp_status_online
                else -> R.string.smtp_status_offline
            }
            tv.setText(getString(res, status))
            tv.visibility = View.VISIBLE
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.login_forgot)
            .setMessage(R.string.login_forgot_confirm)
            .setPositiveButton(R.string.delete) { d, _ ->
                d.dismiss()
                Account.reset(this)
                identifierInput.text.clear()
                passwordInput.text.clear()
                confirmInput.text.clear()
                findViewById<TabLayout>(R.id.login_tabs).getTabAt(1)?.select()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun sendWelcome(identifier: String) {
        if (Account.looksLikeEmail(identifier)) {
            ConfirmMail.send(this, identifier, identifier) { result ->
                val res = messageEmailResult(result)
                Toast.makeText(
                    this,
                    res,
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            Toast.makeText(this, R.string.sms_not_configured, Toast.LENGTH_SHORT).show()
        }
    }

    private fun messageEmailResult(result: ConfirmMail.SendResult): Int = when {
        result.ok && result.smtpOnline -> R.string.email_sent
        result.ok && !result.smtpOnline -> R.string.email_sent_smtp_pending
        !result.ok && result.detail.isNullOrBlank() -> R.string.email_send_failed
        else -> R.string.email_send_failed_smtp
    }

    private fun enterGuest() {
        Telemetry.log(this, "LOGIN modo visitante")
        Account.enterGuest(this)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun enterMain() {
        Telemetry.log(this, "LOGIN sucesso, abrindo MainActivity")
        Account.setLoggedIn(this, true)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}
