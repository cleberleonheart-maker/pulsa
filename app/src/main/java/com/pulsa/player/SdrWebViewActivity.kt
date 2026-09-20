package com.pulsa.player

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry

class SdrWebViewActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var progress: ProgressBar? = null
    private var playing = true
    private var sdrUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sdr)
        AnimatedBackground.apply(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = findViewById(R.id.sdr_web)
        progress = findViewById(R.id.sdr_progress)
        findViewById<ImageButton>(R.id.btn_sdr_back).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btn_sdr_pause).setOnClickListener { togglePause() }

        val url = intent.getStringExtra("sdr_url") ?: "https://websdr.ewi.utwente.nl:8901/"
        sdrUrl = url
        setupWebView(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(url: String) {
        val wv = webView ?: return
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.databaseEnabled = true
        wv.settings.mediaPlaybackRequiresUserGesture = false
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        wv.settings.builtInZoomControls = true
        wv.settings.displayZoomControls = false
        wv.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val u = request?.url ?: Uri.parse(url)
                val scheme = u.scheme ?: "http"
                if (scheme == "http" || scheme == "https" || scheme == "ws" || scheme == "wss") {
                    return false
                }
                return true && run {
                    val base = u.toString()
                    if (base.startsWith("tel:") || base.startsWith("mailto:")) {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, u))
                        } catch (_: Exception) {}
                    }
                    true
                }
            }
            override fun onReceivedError(
                view: WebView?, request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                val code = error?.errorCode ?: -1
                if (code == -1) return
                Telemetry.log(this@SdrWebViewActivity, "SDR erro http $code ${request?.url}")
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress?.let { p ->
                    if (newProgress < 100) {
                        p.visibility = View.VISIBLE
                        p.progress = newProgress
                    } else {
                        p.visibility = View.GONE
                    }
                }
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
        wv.loadUrl(url)
        Telemetry.log(this, "SDR abre: $url")
    }

    private fun togglePause() {
        val wv = webView ?: return
        playing = !playing
        val btn = findViewById<ImageButton>(R.id.btn_sdr_pause)
        if (playing) {
            btn.setImageResource(R.drawable.ic_pause)
            btn.contentDescription = getString(R.string.sdr_pause)
            sdrUrl?.let {
                try {
                    wv.resumeTimers()
                    wv.onResume()
                } catch (_: Exception) {
                }
            }
        } else {
            btn.setImageResource(R.drawable.ic_play)
            btn.contentDescription = getString(R.string.radio_play)
            try {
                wv.pauseTimers()
                wv.onPause()
                wv.evaluateJavascript(
                    "document.querySelectorAll('audio,video').forEach(a=>{a.pause();a.muted=true;})",
                    null
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!playing) return
        webView?.let {
            try {
                it.resumeTimers()
                it.onResume()
            } catch (_: Exception) {
            }
        }
    }

    override fun onPause() {
        try {
            webView?.pauseTimers()
            webView?.onPause()
            webView?.evaluateJavascript(
                "document.querySelectorAll('audio,video').forEach(a=>{a.pause();a.muted=true;})",
                null
            )
        } catch (_: Exception) {
        }
        super.onPause()
    }

    override fun onDestroy() {
        webView?.let {
            try {
                it.stopLoading()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    override fun onBackPressed() {
        val wv = webView
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        fun open(activity: android.app.Activity, url: String) {
            activity.startActivity(
                Intent(activity, SdrWebViewActivity::class.java).putExtra("sdr_url", url)
            )
        }
    }
}
