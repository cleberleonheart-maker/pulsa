package com.pulsa.player
import com.pulsa.player.util.UpdateChecker
import com.pulsa.player.audio.SilenceSkipper
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pulsa.player.dj.DjLearn
import com.pulsa.player.dj.DjMemory
import com.pulsa.player.dj.Hotword
import com.pulsa.player.playback.Playback
import com.pulsa.player.core.Account
import com.pulsa.player.ui.AnimatedBackground
import com.pulsa.player.core.Changelog
import com.pulsa.player.core.Helper
import com.pulsa.player.core.KidsMode
import com.pulsa.player.sync.Handoff
import com.pulsa.player.sync.MirrorQr
import com.pulsa.player.sync.MirrorSync
import com.pulsa.player.sync.Net
import com.pulsa.player.sync.RemoteControl
import com.pulsa.player.sync.RemoteProtocol
import com.pulsa.player.work.PulsaWork
import com.pulsa.player.media.UrlDownloader
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.data.PlaylistBackup
import com.pulsa.player.data.PlaylistDb
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import com.pulsa.player.ui.settings.bindAppearance
import com.pulsa.player.ui.settings.bindAudio
import com.pulsa.player.ui.settings.bindSync
import com.pulsa.player.ui.settings.bindDj
import com.pulsa.player.ui.settings.bindDownloads
import com.pulsa.player.ui.settings.bindMaintenance
import com.pulsa.player.ui.settings.bindAccount
import com.pulsa.player.ui.settings.joinWithCode
import com.pulsa.player.ui.settings.buildBackup
import com.pulsa.player.ui.settings.applyRestore
import com.pulsa.player.ui.settings.showRestoreReport
import com.pulsa.player.ui.settings.SettingsSectionNav
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private var sectionNav: SettingsSectionNav? = null
    private val swipeSlop by lazy { 72f * resources.displayMetrics.density }
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeActive = false
    private var swipeBlocked = false

    internal val backupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri == null) return@registerForActivityResult
            // Fora da main: `PlaylistBackup.export` varre a biblioteca uma vez para traduzir os
            // ids das correções de nome em caminho, e isso é uma consulta ao MediaStore. Com a
            // biblioteca grande, fazer isso no callback do launcher trava a tela.
            ThreadPool.post {
                val json = runCatching { buildBackup().toString() }.getOrElse {
                    ThreadPool.onUi {
                        Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }
                ThreadPool.onUi {
                    runCatching {
                        contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        Toast.makeText(this, R.string.backup_done, Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

    internal val restoreLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            // Fora da main pelo mesmo motivo do backup: resolver caminho → faixa é MediaStore.
            // O restore inteiro vai junto, para o trabalho antigo (que era rápido e síncrono)
            // não ficar metade na main e metade fora — um `Playback.refreshFx()` disparado no
            // meio do restore mostraria o som antigo enquanto a biblioteca já mudou.
            ThreadPool.post {
                val applied = runCatching {
                    val text = contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: return@post
                    applyRestore(text)
                }.getOrNull() ?: return@post
                ThreadPool.onUi {
                    if (applied.settingsChanged) Playback.refreshFx()
                    showRestoreReport(applied.report)
                }
            }
        }

    // Mãos-livres precisa do microfone; se negado, o switch volta desligado.
    internal val hotwordLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val sw = findViewById<MaterialSwitch>(R.id.hotword_switch)
            sw.isChecked = granted
            Settings.setHotword(this, granted)
            if (granted) Hotword.startIfNeeded(this) else Hotword.stopIfRunning(this)
            if (!granted) {
                Toast.makeText(this, R.string.hands_free_no_mic, Toast.LENGTH_LONG).show()
            }
        }

    /** QR lido pelo convidado: entra na sessão se o conteúdo for o código do Pulsa. */
    internal val scanLauncher =
        registerForActivityResult(ScanContract()) { result ->
            val contents = result?.contents
            if (contents == null) return@registerForActivityResult
            val code = MirrorQr.codeFrom(contents)
            if (code == null) {
                Toast.makeText(this, R.string.mirror_scan_invalid, Toast.LENGTH_SHORT).show()
            } else {
                joinWithCode(code)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Settings.accentStyle(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_settings_back).setOnClickListener { finish() }

        // Seções extraídas
        bindAppearance()
        bindAudio()
        bindSync()
        bindDj()
        bindDownloads()
        bindMaintenance()
        bindAccount()

        sectionNav = SettingsSectionNav(this).also { it.setup() }
    }

    /**
     * Abas horizontais: deslizar troca de seção. O toque é cancelado na view de baixo para não
     * virar clique acidental; sliders horizontais ficam de fora para não perderem o arrasto.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val nav = sectionNav
        if (nav != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = ev.rawX
                    swipeStartY = ev.rawY
                    swipeActive = false
                    swipeBlocked = touchStartsOnSeekBar(ev)
                }

                MotionEvent.ACTION_MOVE -> if (!swipeActive && !swipeBlocked) {
                    val dx = ev.rawX - swipeStartX
                    val dy = ev.rawY - swipeStartY
                    if (abs(dx) > swipeSlop && abs(dx) > abs(dy) * 1.2f) {
                        swipeActive = true
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        nav.goBy(if (dx < 0) 1 else -1)
                        return true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swipeActive) {
                    swipeActive = false
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun touchStartsOnSeekBar(ev: MotionEvent): Boolean {
        val location = IntArray(2)
        for (id in intArrayOf(R.id.audio_3d_depth_seek, R.id.surround_intensity_seek)) {
            val view = findViewById<View>(id) ?: continue
            if (view.visibility != View.VISIBLE) continue
            view.getLocationOnScreen(location)
            val insideX = ev.rawX >= location[0] && ev.rawX <= location[0] + view.width
            val insideY = ev.rawY >= location[1] && ev.rawY <= location[1] + view.height
            if (insideX && insideY) return true
        }
        return false
    }

    override fun onStart() {
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        window.decorView.setBackgroundResource(R.drawable.bg_aurora)
        AnimatedBackground.stop()
    }

    override fun onStop() {
        AnimatedBackground.stop()
        super.onStop()
    }

    /**
     * Busca segura: devolve `null` em vez de estourar quando o id some do layout.
     *
     * `findViewById` devolve tipo plataforma (`TextView!`) e o Kotlin só insere o check-null no
     * ponto do uso, então a falta do id estourava como NPE em `TextView.setText` — longe da
     * causa. Com o id no log dá para ver o que falta em vez de adivinhar pela linha do
     * stack trace.
     */
    internal inline fun <reified T : View> viewOrNull(id: Int): T? {
        val found = findViewById<T>(id)
        if (found == null) {
            Log.e(TAG, "view ausente no layout: " + resources.getResourceEntryName(id))
        }
        return found
    }

    internal companion object {
        const val TAG = "PulsaSettings"
    }
}
