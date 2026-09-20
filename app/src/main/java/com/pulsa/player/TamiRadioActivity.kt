package com.pulsa.player

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.pulsa.player.core.Settings
import com.pulsa.player.dj.TamiRadio
import com.pulsa.player.ui.AnimatedBackground

class TamiRadioActivity : AppCompatActivity() {

    private lateinit var toggleBtn: ImageButton
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tami_radio)
        AnimatedBackground.apply(this)

        findViewById<View>(R.id.btn_radio_back).setOnClickListener { finish() }

        toggleBtn = findViewById(R.id.tami_btn_toggle)
        statusText = findViewById(R.id.tami_status)

        toggleBtn.setOnClickListener {
            if (TamiRadio.isActive) TamiRadio.stop(this) else TamiRadio.start(this)
        }
        TamiRadio.bind {
            if (!isFinishing && !isDestroyed) render()
        }
        render()
    }

    override fun onDestroy() {
        TamiRadio.bind(null)
        super.onDestroy()
    }

    private fun render() {
        val on = TamiRadio.isActive
        statusText.setText(if (on) R.string.tami_radio_status_on else R.string.tami_radio_status_off)
        toggleBtn.setImageResource(if (on) R.drawable.ic_pause else R.drawable.ic_play)
        toggleBtn.contentDescription = getString(
            if (on) R.string.tami_radio_stop else R.string.tami_radio_start
        )
        toggleBtn.setColorFilter(ContextCompat.getColor(this, R.color.text_primary))
    }
}