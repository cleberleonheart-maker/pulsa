package com.pulsa.player.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pulsa.player.R

object Settings {
    private const val FILE = "pulsa_settings"
    private const val SECRET_FILE = "pulsa_secrets"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MIRROR_CODE = "mirror_code"
    private const val KEY_MIRROR_HOST = "mirror_host"
    private const val KEY_SERVER = "server_base"
    private const val DEFAULT_TELEMETRY_TOKEN = "pulsa-local-2026"

    const val ACCENT_PURPLE = "purple"
    const val ACCENT_BLUE = "blue"
    const val ACCENT_GREEN = "green"
    const val ACCENT_AMBER = "amber"
    const val ACCENT_RED = "red"
    const val ACCENT_PINK = "pink"
    const val ACCENT_TEAL = "teal"

    const val QUALITY_AUTO = "auto"
    const val QUALITY_DEFAULT = "default"
    const val QUALITY_BASS = "bass"
    const val QUALITY_VOICES = "voices"
    const val QUALITY_TREBLE = "treble"
    const val QUALITY_ROCK = "rock"
    const val QUALITY_DANCE = "dance"
    const val QUALITY_POP = "pop"
    const val QUALITY_JAZZ = "jazz"
    const val QUALITY_ACOUSTIC = "acoustic"
    const val QUALITY_CLASSICAL = "classical"
    const val QUALITY_LOUDNESS = "loudness"
    const val DANCE_SPEED = 1.12f
    const val DANCE_PITCH = 1.06f

    const val CROSSFADE_OFF = "off"
    const val CROSSFADE_SHORT = "short"
    const val CROSSFADE_MEDIUM = "medium"
    const val CROSSFADE_LONG = "long"

    const val LANG_PT = "pt"
    const val LANG_EN = "en"
    const val LANG_ES = "es"

    const val DJ_ALL = "all"
    const val DJ_FAVORITES = "favorites"

    const val DJ_CALM = "calm"
    const val DJ_BALANCED = "balanced"
    const val DJ_WILD = "wild"

    const val SKIN_OFF = "off"
    const val SKIN_NEON = "neon"
    const val SKIN_AURORA = "aurora"
    const val SKIN_PARTICLES = "particles"
    val SKIN_ORDER = arrayOf(SKIN_OFF, SKIN_NEON, SKIN_AURORA, SKIN_PARTICLES)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Prefs de dados/controle interno (não sensível). */
    fun dataPrefs(context: Context): SharedPreferences = prefs(context)

    /** ID anônimo único do aparelho (UUID), para distinguir usuários na telemetria. */
    fun deviceId(context: Context): String {
        val sp = prefs(context)
        sp.getString(KEY_DEVICE_ID, null)?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        sp.edit().putString(KEY_DEVICE_ID, id).apply()
        return id
    }

    /** Código da sessão "ouvir juntos" (vazio = sem sessão). */
    fun mirrorCode(context: Context): String =
        prefs(context).getString(KEY_MIRROR_CODE, "") ?: ""

    fun setMirrorCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_MIRROR_CODE, code.trim().uppercase()).apply()
    }

    /** True se este aparelho criou a sessão (anfitrião) em vez de apenas entrar. */
    fun mirrorHost(context: Context): Boolean = prefs(context).getBoolean(KEY_MIRROR_HOST, false)

    fun setMirrorHost(context: Context, host: Boolean) {
        prefs(context).edit().putBoolean(KEY_MIRROR_HOST, host).apply()
    }

    /** Endereço do servidor de telemetria (vazio = padrão da LAN/localhost). */
    fun serverBase(context: Context): String = prefs(context).getString(KEY_SERVER, "") ?: ""

    fun setServerBase(context: Context, url: String) {
        val cleaned = url.trim().trimEnd('/')
        prefs(context).edit().putString(KEY_SERVER, cleaned).apply()
    }

    /** Hosts a tentar, na ordem: configurado primeiro, depois LAN e localhost. */
    fun serverCandidates(context: Context): List<String> {
        val out = ArrayList<String>(3)
        val custom = serverBase(context)
        if (custom.isNotEmpty() && !out.contains(custom)) out.add(custom)
        for (def in listOf("http://192.168.100.7:8081", "http://127.0.0.1:8081")) {
            if (!out.contains(def)) out.add(def)
        }
        return out
    }

    /** Prefs criptografadas (Android Keystore) para chaves/secrets/sessão. */
    private fun secretPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            SECRET_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /** Lê um secret, migrando o valor em texto puro (das versões antigas) na primeira leitura. */
    private fun secret(context: Context, key: String): String {
        val sp = secretPrefs(context)
        val stored = sp.getString(key, null)
        if (stored != null) return stored
        val legacy = prefs(context).getString(key, "") ?: ""
        if (legacy.isNotEmpty()) sp.edit().putString(key, legacy).apply()
        return legacy
    }

    private fun setSecret(context: Context, key: String, value: String) {
        secretPrefs(context).edit().putString(key, value.trim()).apply()
        prefs(context).edit().remove(key).apply()
    }

    fun telemetryToken(context: Context): String =
        secret(context, "telemetry_token").ifEmpty { DEFAULT_TELEMETRY_TOKEN }

    fun setAccent(context: Context, value: String) {
        prefs(context).edit().putString("accent", value).apply()
    }

    fun accent(context: Context): String =
        prefs(context).getString("accent", ACCENT_PURPLE) ?: ACCENT_PURPLE

    fun accentStyle(context: Context): Int = when (accent(context)) {
        ACCENT_BLUE -> R.style.Theme_Pulsa_Blue
        ACCENT_GREEN -> R.style.Theme_Pulsa_Green
        ACCENT_AMBER -> R.style.Theme_Pulsa_Amber
        ACCENT_RED -> R.style.Theme_Pulsa_Red
        ACCENT_PINK -> R.style.Theme_Pulsa_Pink
        ACCENT_TEAL -> R.style.Theme_Pulsa_Teal
        else -> R.style.Theme_Pulsa
    }

    fun accentLabelRes(accent: String): Int = when (accent) {
        ACCENT_BLUE -> R.string.accent_blue
        ACCENT_GREEN -> R.string.accent_green
        ACCENT_AMBER -> R.string.accent_amber
        ACCENT_RED -> R.string.accent_red
        ACCENT_PINK -> R.string.accent_pink
        ACCENT_TEAL -> R.string.accent_teal
        else -> R.string.accent_purple
    }

    fun setAnimatedBg(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("animated_bg", value).apply()
    }

    fun animatedBg(context: Context): Boolean =
        prefs(context).getBoolean("animated_bg", true)

    fun setStarsOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("stars_on", value).apply()
    }

    fun starsOn(context: Context): Boolean =
        prefs(context).getBoolean("stars_on", true)

    fun setSortAlphabetical(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("sort_alpha", value).apply()
    }

    fun sortAlphabetical(context: Context): Boolean =
        prefs(context).getBoolean("sort_alpha", false)

    fun setEqualizerOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("equalizer_on", value).apply()
    }

    fun equalizerOn(context: Context): Boolean =
        prefs(context).getBoolean("equalizer_on", false)

    fun setAudio8d(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("audio_8d", value).apply()
    }

    fun audio8d(context: Context): Boolean =
        prefs(context).getBoolean("audio_8d", false)

    fun setAudioQuality(context: Context, value: String) {
        prefs(context).edit().putString("audio_quality", value).apply()
    }

    fun audioQuality(context: Context): String =
        prefs(context).getString("audio_quality", QUALITY_DEFAULT) ?: QUALITY_DEFAULT

    fun danceSpeed(context: Context): Float =
        if (audioQuality(context) == QUALITY_DANCE) DANCE_SPEED else 1f

    fun dancePitch(context: Context): Float =
        if (audioQuality(context) == QUALITY_DANCE) DANCE_PITCH else 1f

    fun qualityLabelRes(quality: String): Int = when (quality) {
        QUALITY_AUTO -> R.string.quality_auto
        QUALITY_BASS -> R.string.quality_bass
        QUALITY_VOICES -> R.string.quality_voices
        QUALITY_TREBLE -> R.string.quality_treble
        QUALITY_ROCK -> R.string.quality_rock
        QUALITY_DANCE -> R.string.quality_dance
        QUALITY_POP -> R.string.quality_pop
        QUALITY_JAZZ -> R.string.quality_jazz
        QUALITY_ACOUSTIC -> R.string.quality_acoustic
        QUALITY_CLASSICAL -> R.string.quality_classical
        QUALITY_LOUDNESS -> R.string.quality_loudness
        else -> R.string.quality_default
    }

    fun setCrossfade(context: Context, value: String) {
        prefs(context).edit().putString("crossfade", value).apply()
    }

    fun crossfade(context: Context): String =
        prefs(context).getString("crossfade", CROSSFADE_OFF) ?: CROSSFADE_OFF

    fun setLanguage(context: Context, value: String) {
        prefs(context).edit().putString("language", value).apply()
    }

    fun language(context: Context): String =
        prefs(context).getString("language", LANG_PT) ?: LANG_PT

    fun languageLabelRes(language: String): Int = when (language) {
        LANG_EN -> R.string.language_en
        LANG_ES -> R.string.language_es
        else -> R.string.language_pt
    }

    fun languageTag(language: String): String = when (language) {
        LANG_EN -> "en"
        LANG_ES -> "es"
        else -> "pt"
    }

    fun crossfadeMs(value: String): Int = when (value) {
        CROSSFADE_SHORT -> 2000
        CROSSFADE_MEDIUM -> 5000
        CROSSFADE_LONG -> 8000
        else -> 0
    }

    fun crossfadeLabelRes(value: String): Int = when (value) {
        CROSSFADE_SHORT -> R.string.crossfade_short
        CROSSFADE_MEDIUM -> R.string.crossfade_medium
        CROSSFADE_LONG -> R.string.crossfade_long
        else -> R.string.crossfade_off
    }

    fun setDjSource(context: Context, value: String) {
        prefs(context).edit().putString("dj_source", value).apply()
    }

    fun djSource(context: Context): String =
        prefs(context).getString("dj_source", DJ_ALL) ?: DJ_ALL

    fun setDjIntensity(context: Context, value: String) {
        prefs(context).edit().putString("dj_intensity", value).apply()
    }

    fun djIntensity(context: Context): String =
        prefs(context).getString("dj_intensity", DJ_BALANCED) ?: DJ_BALANCED

    fun setDjVoice(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("dj_voice", value).apply()
    }

    fun djVoice(context: Context): Boolean =
        prefs(context).getBoolean("dj_voice", true)

    fun setDjRadio(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("dj_radio", value).apply()
    }

    fun djRadio(context: Context): Boolean =
        prefs(context).getBoolean("dj_radio", true)

    fun setRecToken(context: Context, value: String) {
        prefs(context).edit().putString("rec_token", value.trim()).apply()
    }

    fun recToken(context: Context): String =
        prefs(context).getString("rec_token", "") ?: ""

    fun setPendriveTreeUri(context: Context, value: String) {
        prefs(context).edit().putString("pendrive_tree_uri", value).apply()
    }

    fun pendriveTreeUri(context: Context): String? =
        prefs(context).getString("pendrive_tree_uri", null)

    fun setVisualizerOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("visualizer_on", value).apply()
    }

    fun visualizerOn(context: Context): Boolean =
        prefs(context).getBoolean("visualizer_on", true)

    fun setSkin(context: Context, value: String) {
        prefs(context).edit().putString("player_skin", value).apply()
    }

    fun skin(context: Context): String =
        prefs(context).getString("player_skin", SKIN_NEON) ?: SKIN_NEON

    fun setKaraokeOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("karaoke_on", value).apply()
    }

    fun karaokeOn(context: Context): Boolean =
        prefs(context).getBoolean("karaoke_on", false)

    fun setGeminiKey(context: Context, value: String) {
        setSecret(context, "gemini_key", value)
    }

    fun geminiKey(context: Context): String =
        secret(context, "gemini_key")

    fun setGeminiOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("gemini_on", value).apply()
    }

    fun geminiOn(context: Context): Boolean =
        prefs(context).getBoolean("gemini_on", false)

    fun setResumeOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("resume_on", value).apply()
    }

    fun resumeOn(context: Context): Boolean =
        prefs(context).getBoolean("resume_on", true)

    fun setGesturesOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("gestures_on", value).apply()
    }

    fun gesturesOn(context: Context): Boolean =
        prefs(context).getBoolean("gestures_on", false)

    fun setCustomEqOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("custom_eq_on", value).apply()
    }

    fun customEqOn(context: Context): Boolean =
        prefs(context).getBoolean("custom_eq_on", false)

    fun setCustomEqBands(context: Context, value: String) {
        prefs(context).edit().putString("custom_eq_bands", value).apply()
    }

    fun customEqBands(context: Context): String =
        prefs(context).getString("custom_eq_bands", "0,0,0,0,0") ?: "0,0,0,0,0"

    fun setLastFmKey(context: Context, value: String) {
        setSecret(context, "lastfm_key", value)
    }

    fun lastFmKey(context: Context): String =
        secret(context, "lastfm_key")

    fun setLastFmSecret(context: Context, value: String) {
        setSecret(context, "lastfm_secret", value)
    }

    fun lastFmSecret(context: Context): String =
        secret(context, "lastfm_secret")

    fun setLastFmUser(context: Context, value: String) {
        prefs(context).edit().putString("lastfm_user", value.trim()).apply()
    }

    fun lastFmUser(context: Context): String =
        prefs(context).getString("lastfm_user", "") ?: ""

    fun setLastFmSession(context: Context, value: String) {
        setSecret(context, "lastfm_session", value)
    }

    fun lastFmSession(context: Context): String =
        secret(context, "lastfm_session")

    // Última reprodução salva (retomar onde parou)
    fun setResumeState(context: Context, songId: Long, positionMs: Long, songTitle: String, songArtist: String) {
        prefs(context).edit()
            .putLong("resume_song_id", songId)
            .putLong("resume_position", positionMs)
            .putString("resume_title", songTitle)
            .putString("resume_artist", songArtist)
            .apply()
    }

    fun resumeSongId(context: Context): Long = prefs(context).getLong("resume_song_id", -1L)

    fun resumePosition(context: Context): Long = prefs(context).getLong("resume_position", 0L)

    fun resumeSongTitle(context: Context): String = prefs(context).getString("resume_title", "") ?: ""

    fun resumeSongArtist(context: Context): String = prefs(context).getString("resume_artist", "") ?: ""

    private fun isArtDir(file: java.io.File): Boolean = file.isDirectory && file.name == "art"

    fun cacheSize(context: Context): Long {
        return context.cacheDir.walkTopDown()
            .filter { it.isFile && !it.path.contains("/art/") }
            .sumOf { it.length() }
    }

    fun clearCache(context: Context): Long {
        var freed = 0L
        context.cacheDir.listFiles()?.forEach { file ->
            if (isArtDir(file)) return@forEach
            freed += if (file.isDirectory) {
                val size = file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                file.deleteRecursively()
                size
            } else {
                val size = file.length()
                file.delete()
                size
            }
        }
        return freed
    }
}