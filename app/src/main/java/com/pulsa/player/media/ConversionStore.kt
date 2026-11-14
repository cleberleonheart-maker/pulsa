package com.pulsa.player.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

object ConversionStore {

    enum class Status {
        QUEUED,
        RUNNING,
        FINALIZING,
        DONE,
        FAILED,
        CANCELLED
    }

    data class Item(
        val id: String,
        val title: String,
        val sourceUri: String,
        val sourceType: ConverterSourceType,
        val output: ConverterOutput,
        val resolution: ConverterResolution,
        val quality: ConverterQuality,
        val trimStartMs: Long,
        val trimEndMs: Long,
        val removeAudio: Boolean,
        val durationMs: Long,
        val sourceSizeBytes: Long,
        val status: Status,
        val progress: Int,
        val outputUri: String,
        val outputMime: String,
        val outputId: Long,
        val outputBytes: Long,
        val error: String?,
        val createdAt: Long,
        /** A largura da fonte: com ela, o bitrate e a escala do vídeo saem certos. */
        val sourceWidth: Int = 0,
        val sourceHeight: Int = 0
    ) {
        fun isActive(): Boolean = status == Status.QUEUED ||
            status == Status.RUNNING || status == Status.FINALIZING
        fun isDone(): Boolean = status == Status.DONE
    }

    private val items = ArrayList<Item>()
    private val ui = Handler(Looper.getMainLooper())
    private val observers = ArrayList<() -> Unit>()
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "conversions-store").apply { isDaemon = true }
    }
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val parsed = runCatching { read(context) }.getOrDefault(emptyList())
        synchronized(items) {
            items.clear()
            items.addAll(parsed)
        }
    }

    fun list(): List<Item> = synchronized(items) {
        items.sortedWith(compareBy({ if (it.isActive()) 0 else 1 }, { -it.createdAt }))
    }

    fun byId(id: String): Item? = synchronized(items) { items.firstOrNull { it.id == id } }

    fun addObserver(observer: () -> Unit) = synchronized(observers) {
        if (!observers.contains(observer)) observers.add(observer)
    }

    fun removeObserver(observer: () -> Unit) = synchronized(observers) {
        observers.remove(observer)
    }

    fun put(context: Context, item: Item, persist: Boolean = true) {
        synchronized(items) {
            val index = items.indexOfFirst { it.id == item.id }
            if (index >= 0) items[index] = item else items.add(item)
        }
        if (persist) save(context)
        notifyChanged()
    }

    fun remove(context: Context, id: String) {
        val item = byId(id) ?: return
        if (item.outputUri.isNotBlank()) {
            runCatching { context.contentResolver.delete(Uri.parse(item.outputUri), null, null) }
        }
        synchronized(items) { items.removeAll { it.id == id } }
        save(context)
        notifyChanged()
    }

    fun usedBytes(): Long = list().sumOf { it.outputBytes.coerceAtLeast(0L) }

    internal fun toJson(item: Item): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("sourceUri", item.sourceUri)
        put("sourceType", item.sourceType.name)
        put("output", item.output.name)
        put("resolution", item.resolution.name)
        put("quality", item.quality.name)
        put("trimStartMs", item.trimStartMs)
        put("trimEndMs", item.trimEndMs)
        put("removeAudio", item.removeAudio)
        put("durationMs", item.durationMs)
        put("sourceSizeBytes", item.sourceSizeBytes)
        put("status", item.status.name)
        put("progress", item.progress)
        put("outputUri", item.outputUri)
        put("outputMime", item.outputMime)
        put("outputId", item.outputId)
        put("outputBytes", item.outputBytes)
        put("error", item.error ?: JSONObject.NULL)
        put("createdAt", item.createdAt)
        put("sourceWidth", item.sourceWidth)
        put("sourceHeight", item.sourceHeight)
    }

    internal fun fromJson(json: String): Item? = runCatching {
        fromJsonObject(JSONObject(json))
    }.getOrNull()

    private fun fromJsonObject(json: JSONObject): Item {
        val item = Item(
            id = json.optString("id"),
            title = json.optString("title"),
            sourceUri = json.optString("sourceUri"),
            sourceType = runCatching {
                ConverterSourceType.valueOf(json.optString("sourceType"))
            }.getOrDefault(ConverterSourceType.LOCAL),
            output = runCatching {
                ConverterOutput.valueOf(json.optString("output"))
            }.getOrDefault(ConverterOutput.MP4),
            resolution = runCatching {
                ConverterResolution.valueOf(json.optString("resolution"))
            }.getOrDefault(ConverterResolution.ORIGINAL),
            quality = runCatching {
                ConverterQuality.valueOf(json.optString("quality"))
            }.getOrDefault(ConverterQuality.BALANCED),
            trimStartMs = json.optLong("trimStartMs"),
            trimEndMs = json.optLong("trimEndMs"),
            removeAudio = json.optBoolean("removeAudio"),
            durationMs = json.optLong("durationMs"),
            sourceSizeBytes = json.optLong("sourceSizeBytes"),
            status = runCatching {
                Status.valueOf(json.optString("status"))
            }.getOrDefault(Status.FAILED),
            progress = json.optInt("progress"),
            outputUri = json.optString("outputUri"),
            outputMime = json.optString("outputMime"),
            outputId = json.optLong("outputId"),
            outputBytes = json.optLong("outputBytes"),
            error = if (json.isNull("error")) null else json.optString("error"),
            createdAt = json.optLong("createdAt"),
            sourceWidth = json.optInt("sourceWidth"),
            sourceHeight = json.optInt("sourceHeight")
        )
        return if (item.status == Status.RUNNING || item.status == Status.FINALIZING) {
            item.copy(status = Status.FAILED, error = item.error ?: "interrompido")
        } else {
            item
        }
    }

    private fun stateFile(context: Context): File = File(context.filesDir, "conversions.json")

    private fun save(context: Context) {
        val snapshot = synchronized(items) { ArrayList(items) }
        writer.execute {
            runCatching {
                val array = JSONArray()
                snapshot.forEach { array.put(toJson(it)) }
                stateFile(context).writeText(array.toString())
            }
        }
    }

    private fun read(context: Context): List<Item> {
        val text = runCatching { stateFile(context).readText() }.getOrNull() ?: return emptyList()
        val array = JSONArray(text)
        val output = ArrayList<Item>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val item = fromJsonObject(json)
            if (item.id.isNotBlank()) output.add(item)
        }
        return output
    }

    private fun notifyChanged() {
        val snapshot = synchronized(observers) { ArrayList(observers) }
        if (snapshot.isEmpty()) return
        ui.post { snapshot.forEach { runCatching { it() } } }
    }
}
