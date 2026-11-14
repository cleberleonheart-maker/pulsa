package com.pulsa.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.pulsa.player.core.Helper
import com.pulsa.player.core.ThreadPool
import com.pulsa.player.media.ConversionService
import com.pulsa.player.media.ConversionStore
import com.pulsa.player.media.ConverterOptions
import com.pulsa.player.media.ConverterOutput
import com.pulsa.player.media.ConverterQuality
import com.pulsa.player.media.ConverterResolution
import com.pulsa.player.media.ConverterRules
import com.pulsa.player.media.ConverterSourceInfo
import com.pulsa.player.media.ConverterSourceInspector
import com.pulsa.player.media.ConverterSourceType
import com.pulsa.player.media.OfficialMediaResolver
import com.pulsa.player.media.StreamKind
import com.pulsa.player.media.UrlDownloader
import com.pulsa.player.model.Song
import com.pulsa.player.playback.Playback
import com.pulsa.player.ui.adapter.ConversionAdapter
import java.util.UUID

class ConverterActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SOURCE_URI = "source_uri"
        private const val EXTRA_SOURCE_TITLE = "source_title"

        fun start(context: Context, uri: String, title: String) {
            context.startActivity(
                Intent(context, ConverterActivity::class.java)
                    .putExtra(EXTRA_SOURCE_URI, uri)
                    .putExtra(EXTRA_SOURCE_TITLE, title)
            )
        }
    }

    private lateinit var sourceInfoView: TextView
    private lateinit var formatGroup: MaterialButtonToggleGroup
    private lateinit var formatMp4: MaterialButton
    private lateinit var formatM4a: MaterialButton
    private lateinit var resolutionLabel: TextView
    private lateinit var resolution: Spinner
    private lateinit var qualityLabel: TextView
    private lateinit var quality: Spinner
    private lateinit var removeAudio: MaterialSwitch
    private lateinit var trimStart: TextInputEditText
    private lateinit var trimEnd: TextInputEditText
    private lateinit var nameInput: TextInputEditText
    private lateinit var emptyView: TextView
    private lateinit var list: RecyclerView
    private lateinit var adapter: ConversionAdapter

    private var sourceUri: String? = null
    private var sourceType: ConverterSourceType? = null
    private var sourceTitle: String = ""
    private var sourceInfo: ConverterSourceInfo? = null
    private val queueObserver: () -> Unit = { renderQueue() }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        setSource(
            uri = uri.toString(),
            type = ConverterSourceType.LOCAL,
            title = uri.lastPathSegment.orEmpty(),
            info = null
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_converter)
        bindViews()
        setupForm()
        setupQueue()
        val incomingUri = intent.getStringExtra(EXTRA_SOURCE_URI).orEmpty()
        if (incomingUri.isNotBlank()) {
            val title = intent.getStringExtra(EXTRA_SOURCE_TITLE).orEmpty()
            val uri = Uri.parse(incomingUri)
            ThreadPool.post {
                val info = runCatching { ConverterSourceInspector.inspect(this, uri) }.getOrNull()
                ThreadPool.onUi {
                    if (isFinishing || isDestroyed) return@onUi
                    setSource(incomingUri, ConverterSourceType.LOCAL, title, info)
                }
            }
        }
    }

    override fun onDestroy() {
        ConversionStore.removeObserver(queueObserver)
        super.onDestroy()
    }

    private fun bindViews() {
        sourceInfoView = findViewById(R.id.converter_source_info)
        formatGroup = findViewById(R.id.converter_format_group)
        formatMp4 = findViewById(R.id.converter_format_mp4)
        formatM4a = findViewById(R.id.converter_format_m4a)
        resolutionLabel = findViewById(R.id.converter_resolution_label)
        resolution = findViewById(R.id.converter_resolution)
        qualityLabel = findViewById(R.id.converter_quality_label)
        quality = findViewById(R.id.converter_quality)
        removeAudio = findViewById(R.id.converter_remove_audio)
        trimStart = findViewById(R.id.converter_trim_start)
        trimEnd = findViewById(R.id.converter_trim_end)
        nameInput = findViewById(R.id.converter_name)
        emptyView = findViewById(R.id.converter_empty)
        list = findViewById(R.id.converter_list)
    }

    private fun setupForm() {
        findViewById<View>(R.id.converter_back).setOnClickListener { finish() }
        findViewById<View>(R.id.converter_link_btn).setOnClickListener { promptUrl() }
        findViewById<View>(R.id.converter_file_btn).setOnClickListener {
            filePicker.launch(arrayOf("video/*", "audio/*"))
        }
        formatGroup.check(R.id.converter_format_mp4)
        formatGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateOutputControls()
        }
        val resolutionLabels = listOf(
            getString(R.string.converter_resolution_original),
            getString(R.string.converter_resolution_480),
            getString(R.string.converter_resolution_720),
            getString(R.string.converter_resolution_1080)
        )
        resolution.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            resolutionLabels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val qualityLabels = listOf(
            getString(R.string.converter_quality_auto),
            getString(R.string.converter_quality_economy),
            getString(R.string.converter_quality_balanced),
            getString(R.string.converter_quality_high)
        )
        quality.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            qualityLabels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        findViewById<View>(R.id.converter_start).setOnClickListener { startConversion() }
        updateOutputControls()
    }

    private fun setupQueue() {
        ConversionStore.ensureLoaded(this)
        adapter = ConversionAdapter(::playResult, ::showItemMenu)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        ConversionStore.addObserver(queueObserver)
        renderQueue()
    }

    private fun promptUrl() {
        val urlInput = EditText(this).apply {
            hint = getString(R.string.converter_link_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val titleInput = EditText(this).apply {
            hint = getString(R.string.converter_name)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        body.addView(urlInput)
        body.addView(titleInput)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.converter_link)
            .setView(body)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.converter_select_link) { _, _ ->
                resolveUrl(urlInput.text.toString().trim(), titleInput.text.toString().trim())
            }
            .show()
    }

    private fun resolveUrl(url: String, title: String) {
        if (url.isBlank()) {
            toast(R.string.download_invalid_url)
            return
        }
        sourceInfoView.setText(R.string.converter_resolving_link)
        OfficialMediaResolver.resolveAsync(url) { resolved ->
            if (isFinishing || isDestroyed) return@resolveAsync
            if (resolved != null) {
                setSource(
                    uri = resolved.directUrl,
                    type = ConverterSourceType.URL,
                    title = resolved.name ?: title.ifBlank { UrlDownloader.guessName(url) },
                    info = null
                )
                return@resolveAsync
            }
            val validation = UrlDownloader.validate(url)
            if (validation != null) {
                toast(validation)
                sourceInfoView.setText(R.string.converter_source_none)
                return@resolveAsync
            }
            val mime = StreamKind.mimeTypeOf(url)
            if (mime == androidx.media3.common.MimeTypes.APPLICATION_M3U8 ||
                mime == androidx.media3.common.MimeTypes.APPLICATION_MPD
            ) {
                toast(R.string.converter_direct_link_only)
                sourceInfoView.setText(R.string.converter_source_none)
                return@resolveAsync
            }
            setSource(
                uri = url,
                type = ConverterSourceType.URL,
                title = title.ifBlank { UrlDownloader.guessName(url) },
                info = null
            )
        }
    }

    private fun setSource(
        uri: String,
        type: ConverterSourceType,
        title: String,
        info: ConverterSourceInfo?
    ) {
        sourceUri = uri
        sourceType = type
        sourceTitle = title
        sourceInfo = info
        nameInput.setText(ConverterRules.outputName(title, selectedOutput()))
        trimStart.setText("")
        trimEnd.setText("")
        renderSourceInfo()
        updateOutputControls()
        if (info == null && type == ConverterSourceType.LOCAL) {
            val parsed = Uri.parse(uri)
            ThreadPool.post {
                val inspected = runCatching { ConverterSourceInspector.inspect(this, parsed) }.getOrNull()
                ThreadPool.onUi {
                    if (sourceUri != uri || isFinishing || isDestroyed) return@onUi
                    sourceInfo = inspected
                    renderSourceInfo()
                    updateOutputControls()
                }
            }
        }
        if (info == null && type == ConverterSourceType.URL) {
            ThreadPool.post {
                val inspected = runCatching { ConverterSourceInspector.inspectUrl(uri, title) }.getOrNull()
                ThreadPool.onUi {
                    if (sourceUri != uri || isFinishing || isDestroyed) return@onUi
                    sourceInfo = inspected
                    renderSourceInfo()
                    updateOutputControls()
                }
            }
        }
    }

    private fun renderSourceInfo() {
        val info = sourceInfo
        if (info == null) {
            sourceInfoView.text = if (sourceUri.isNullOrBlank()) {
                getString(R.string.converter_source_none)
            } else {
                getString(
                    R.string.converter_source_pending,
                    sourceTitle,
                    getString(R.string.converter_duration_unknown)
                )
            }
            return
        }
        val type = when {
            info.hasVideo && info.hasAudio -> getString(R.string.converter_video_audio)
            info.hasVideo -> getString(R.string.converter_video_only)
            else -> getString(R.string.converter_audio_only)
        }
        val duration = if (info.durationMs > 0L) {
            ConverterRules.formatTime(info.durationMs)
        } else {
            getString(R.string.converter_duration_unknown)
        }
        val size = if (info.sizeBytes > 0L) {
            Helper.formatBytes(info.sizeBytes)
        } else {
            getString(R.string.converter_size_unknown)
        }
        val resolutionText = if (info.width > 0 && info.height > 0) {
            "${info.width}×${info.height}"
        } else {
            "—"
        }
        sourceInfoView.text = getString(
            R.string.converter_source_details,
            info.name,
            type,
            resolutionText,
            duration,
            size
        )
    }

    private fun updateOutputControls() {
        val output = selectedOutput()
        val info = sourceInfo
        // `tracksKnown` falso é "não sei o que tem dentro" — e não pode desligar nada.
        val unknownTracks = info?.tracksKnown != true
        formatMp4.isEnabled = unknownTracks || info?.hasVideo == true
        formatM4a.isEnabled = unknownTracks || info?.hasAudio == true
        if (!unknownTracks && info?.hasVideo == false && formatGroup.checkedButtonId != R.id.converter_format_m4a) {
            formatGroup.check(R.id.converter_format_m4a)
            return
        }
        if (!unknownTracks && info?.hasAudio == false && formatGroup.checkedButtonId != R.id.converter_format_mp4) {
            formatGroup.check(R.id.converter_format_mp4)
            return
        }
        val showVideoOptions = output.isVideo
        resolutionLabel.visibility = if (showVideoOptions) View.VISIBLE else View.GONE
        resolution.visibility = if (showVideoOptions) View.VISIBLE else View.GONE
        qualityLabel.visibility = if (showVideoOptions) View.VISIBLE else View.GONE
        quality.visibility = if (showVideoOptions) View.VISIBLE else View.GONE
        removeAudio.visibility = if (showVideoOptions) View.VISIBLE else View.GONE
    }

    private fun selectedOutput(): ConverterOutput =
        if (formatGroup.checkedButtonId == R.id.converter_format_m4a) {
            ConverterOutput.M4A
        } else {
            ConverterOutput.MP4
        }

    @UnstableApi
    private fun startConversion() {
        val uri = sourceUri
        val type = sourceType
        if (uri.isNullOrBlank() || type == null) {
            toast(R.string.converter_source_required)
            return
        }
        val start = ConverterRules.parseTime(trimStart.text?.toString().orEmpty())
        val end = ConverterRules.parseTime(trimEnd.text?.toString().orEmpty())
        if (start == null || end == null || start < 0L || end < 0L || (end > 0L && end <= start)) {
            toast(R.string.converter_invalid_trim)
            return
        }
        val info = sourceInfo
        if (info != null && info.durationMs > 0L && end > info.durationMs) {
            toast(R.string.converter_trim_too_long)
            return
        }
        val output = selectedOutput()
        if (info != null && info.tracksKnown && output.isVideo && !info.hasVideo) {
            toast(R.string.converter_no_video)
            return
        }
        if (info != null && info.tracksKnown && !output.isVideo && !info.hasAudio) {
            toast(R.string.converter_no_audio)
            return
        }
        val options = ConverterOptions(
            output = output,
            resolution = ConverterResolution.entries[resolution.selectedItemPosition],
            quality = ConverterQuality.entries[quality.selectedItemPosition],
            trimStartMs = start,
            trimEndMs = end,
            removeAudio = removeAudio.isChecked
        )
        val title = ConverterRules.outputName(
            nameInput.text?.toString().orEmpty().ifBlank { sourceTitle },
            output
        )
        val item = ConversionStore.Item(
            id = UUID.randomUUID().toString(),
            title = title,
            sourceUri = uri,
            sourceType = type,
            output = options.output,
            resolution = options.resolution,
            quality = options.quality,
            trimStartMs = options.trimStartMs,
            trimEndMs = options.trimEndMs,
            removeAudio = options.removeAudio,
            durationMs = info?.durationMs ?: 0L,
            sourceSizeBytes = info?.sizeBytes ?: 0L,
            status = ConversionStore.Status.QUEUED,
            progress = 0,
            outputUri = "",
            outputMime = "",
            outputId = 0L,
            outputBytes = 0L,
            error = null,
            createdAt = System.currentTimeMillis(),
            sourceWidth = info?.width ?: 0,
            sourceHeight = info?.height ?: 0
        )
        ConversionService.enqueue(this, item)
        toast(R.string.converter_start_queued)
    }

    private fun renderQueue() {
        if (!::adapter.isInitialized) return
        val items = ConversionStore.list()
        adapter.submit(items)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    @UnstableApi
    private fun playResult(item: ConversionStore.Item) {
        if (!item.isDone() || item.outputUri.isBlank()) return
        if (item.output.isVideo) {
            VideoPlayerActivity.startStream(this, item.outputUri, item.title)
            return
        }
        val id = item.outputId.takeIf { it != 0L }
            ?: (item.outputUri.hashCode().toLong() and 0x7fffffffL)
        Playback.start(
            listOf(
                Song(
                    id = id,
                    title = item.title,
                    artist = "",
                    album = getString(R.string.converter_title),
                    albumId = 0L,
                    durationMs = 0L,
                    path = item.outputUri,
                    year = 0
                )
            ),
            0
        )
        startActivity(Intent(this, NowPlayingActivity::class.java))
    }

    @UnstableApi
    private fun showItemMenu(item: ConversionStore.Item) {
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (item.isActive()) {
            labels += getString(R.string.converter_cancel)
            actions += { ConversionService.cancel(this, item.id) }
        }
        if (item.status == ConversionStore.Status.FAILED ||
            item.status == ConversionStore.Status.CANCELLED
        ) {
            labels += getString(R.string.converter_retry)
            actions += { ConversionService.retry(this, item.id) }
        }
        labels += getString(R.string.converter_delete)
        actions += {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.converter_delete)
                .setMessage(R.string.converter_delete_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.converter_delete) { _, _ ->
                    ConversionService.delete(this, item.id)
                }
                .show()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(item.title)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun toast(res: Int) {
        Toast.makeText(this, getString(res), Toast.LENGTH_SHORT).show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
