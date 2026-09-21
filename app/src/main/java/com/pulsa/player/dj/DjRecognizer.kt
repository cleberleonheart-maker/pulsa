package com.pulsa.player.dj
import com.pulsa.player.core.CrashLogger
import com.pulsa.player.core.Settings
import com.pulsa.player.sync.Telemetry
import com.pulsa.player.core.ThreadPool

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL

object DjRecognizer {

    data class Result(val title: String, val artist: String, val albumName: String?)

    private const val SAMPLE_RATE = 44100
    private const val RECORD_SECONDS = 6
    private const val AUDD_ENDPOINT = "https://api.audd.io/"

    fun recognize(context: Context, onResult: (Result?, error: String?) -> Unit) {
        val token = Settings.recToken(context)
        if (token.isBlank()) {
            ThreadPool.onUi { onResult(null, "no_token") }
            return
        }
        ThreadPool.post {
            try {
                val wav = captureWav()
                if (wav == null) {
                    ThreadPool.onUi { onResult(null, "mic_failed") }
                    return@post
                }
                val json = queryAudD(token, wav)
                if (json == null) {
                    ThreadPool.onUi { onResult(null, "network") }
                    return@post
                }
                val status = runCatching { json.getString("status") }.getOrNull()
                if (status == "error") {
                    val err = runCatching { json.getJSONObject("error") }
                        .getOrNull() ?: runCatching { json.getJSONObject("result") }.getOrNull()
                    val code = runCatching { err?.getInt("error_code") }.getOrDefault(0)
                    ThreadPool.onUi { onResult(null, "audd_error:$code") }
                    return@post
                }
                val result = runCatching { json.getJSONObject("result") }.getOrNull()
                if (result == null) {
                    ThreadPool.onUi { onResult(null, null) }
                    return@post
                }
                val title = runCatching { result.getString("title") }.getOrDefault("").trim()
                val artist = runCatching { result.getString("artist") }.getOrDefault("").trim()
                val album = runCatching { result.getString("album") }.getOrNull()?.trim()
                if (title.isBlank()) {
                    ThreadPool.onUi { onResult(null, null) }
                    return@post
                }
                ThreadPool.onUi { onResult(Result(title, artist, album), null) }
            } catch (t: Throwable) {
                CrashLogger.writeLog(context, "REC: pipe falhou  $t")
                Telemetry.log(context, "REC erro interno $t")
                ThreadPool.onUi { onResult(null, "internal") }
            }
        }
    }

    private fun queryAudD(token: String, wav: ByteArray): JSONObject? {
        val boundary = "----PulsaBoundary" + System.currentTimeMillis()
        val bos = ByteArrayOutputStream()
        val dos = DataOutputStream(bos)
        val appendField = { name: String, value: String ->
            dos.writeBytes("--$boundary\r\n")
            dos.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            dos.writeBytes("$value\r\n")
        }
        dos.writeBytes("--$boundary\r\n")
        dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n")
        dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        dos.write(wav)
        dos.writeBytes("\r\n")
        appendField("api_token", token)
        appendField("return", "timecode,apple_music,deezer,spotify")
        dos.writeBytes("--$boundary--\r\n")
        dos.flush()
        val body = bos.toByteArray()

        val conn = URL(AUDD_ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept-Encoding", "identity")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("Content-Length", body.size.toString())
            conn.connectTimeout = 25000
            conn.readTimeout = 60000
            conn.doOutput = true
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) return null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return runCatching { JSONObject(text) }.getOrNull()
        } finally {
            conn.disconnect()
        }
    }

    private fun captureWav(): ByteArray? {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) return null
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (t: Throwable) {
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            return null
        }
        val totalFrames = SAMPLE_RATE * RECORD_SECONDS
        val samples = ShortArray(totalFrames)
        val buf = ShortArray(bufferSize / 2)
        try {
            record.startRecording()
            var framesRead = 0
            while (framesRead < totalFrames) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n < 0) throw IllegalStateException("mic read failed ($n)")
                if (n == 0) continue
                val room = totalFrames - framesRead
                buf.copyInto(samples, framesRead, 0, n.coerceAtMost(room))
                framesRead += n
            }
        } catch (t: Throwable) {
            runCatching { record.stop() }
            runCatching { record.release() }
            return null
        }
        runCatching { record.stop() }
        runCatching { record.release() }

        // ---- normalização de ganho (gravações fracas/distantes) ----
        var peak = 0
        for (i in samples.indices) {
            val a = Math.abs(samples[i].toInt())
            if (a > peak) peak = a
        }
        val gain = if (peak == 0) 1.0 else (0.80 * 32767 / peak).coerceAtMost(4.0)
        val pcm = ByteArrayOutputStream(totalFrames * 2)
        for (i in samples.indices) {
            val v = (samples[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm.write(v and 0xFF)
            pcm.write((v shr 8) and 0xFF)
        }
        return withWavHeader(pcm.toByteArray())
    }

    private fun withWavHeader(pcm: ByteArray): ByteArray {
        val dataSize = pcm.size
        val total = 44 + dataSize
        val out = ByteArray(total)
        val put = { text: String, offset: Int ->
            text.toByteArray(Charsets.US_ASCII).copyInto(out, offset)
        }
        val putLe = { value: Int, offset: Int, bytes: Int ->
            var v = value
            for (i in 0 until bytes) {
                out[offset + i] = (v and 0xFF).toByte()
                v = v shr 8
            }
        }
        put("RIFF", 0)
        putLe(total - 8, 4, 4)
        put("WAVE", 8)
        put("fmt ", 12)
        putLe(16, 16, 4)
        putLe(1, 20, 2)
        putLe(1, 22, 2)
        putLe(SAMPLE_RATE, 24, 4)
        putLe(SAMPLE_RATE * 2, 28, 4)
        putLe(2, 32, 2)
        putLe(16, 34, 2)
        put("data", 36)
        putLe(dataSize, 40, 4)
        pcm.copyInto(out, 44, 0, dataSize)
        return out
    }
}
