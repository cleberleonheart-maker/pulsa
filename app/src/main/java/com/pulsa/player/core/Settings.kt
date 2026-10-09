package com.pulsa.player.core

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pulsa.player.R
import com.pulsa.player.playback.QueueKey

object Settings {
    // Ids de avatar. Ficam publicos porque as telas desenham o avatar e a voz por causa
    // deles; o numero e gravado em prefs, entao nao pode mudar depois.
    const val VIRGIN = 0
    const val VICTOR = 1
    const val VERA = 2

    private const val FILE = "pulsa_settings"
    private const val SECRET_FILE = "pulsa_secrets"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_MIRROR_CODE = "mirror_code"
    private const val KEY_MIRROR_HOST = "mirror_host"
    private const val KEY_SERVER = "server_base"
    private const val KEY_TUNNEL_URL = "tunnel_url"
    private const val TUNNEL_INFO_HOST = "https://raw.githubusercontent.com/cleberleonheart-maker/pulsaweb/main/tunnel.txt"
    private const val KEY_ALARM_HOUR = "alarm_hour"
    private const val KEY_ALARM_MINUTE = "alarm_minute"
    private const val KEY_ALARM_AMBIENT = "alarm_ambient"

    const val SERVER_LAN = ""
    const val SERVER_ONLINE = "auto"
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
    const val SKIN_NEBULA = "nebula"
    val SKIN_ORDER = arrayOf(SKIN_OFF, SKIN_NEON, SKIN_AURORA, SKIN_PARTICLES, SKIN_NEBULA)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * URI do arquivo de log espelhado no `Download`, guardado para ser reaproveitado.
     *
     * O CrashLogger não consegue reencontrá-lo por nome: o MediaStore acrescenta a extensão
     * do MIME ("pulsa-erros.log" vira "pulsa-erros.log.txt") e em `MediaStore.Downloads`
     * não dá para filtrar por `RELATIVE_PATH`. Cada busca voltava vazia e o espelho
     * virava um arquivo novo por erro. Guardando o URI, a escrita reaproveita o mesmo.
     */
    fun mirrorLogUri(context: Context): String? =
        prefs(context).getString("mirror_log_uri", null)?.takeIf { it.isNotBlank() }

    fun setMirrorLogUri(context: Context, uri: String?) {
        prefs(context).edit().apply {
            if (uri.isNullOrBlank()) remove("mirror_log_uri") else putString("mirror_log_uri", uri)
        }.apply()
    }

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

    /** "lan" | "online" (auto via túnel) | "custom". */
    fun serverMode(context: Context): String {
        val base = serverBase(context)
        return when {
            base.isEmpty() -> "lan"
            base == SERVER_ONLINE -> "online"
            else -> "custom"
        }
    }

    private var cachedTunnel: String? = null
    private var lastTunnelFetch = 0L

    /** URL pública atual do túnel, lida do pulsaweb (cache de 120s). */
    fun onlineServer(context: Context): String? {
        val now = System.currentTimeMillis()
        if (cachedTunnel != null && now - lastTunnelFetch < 120_000L) return cachedTunnel
        var url: String? = null
        try {
            val conn = java.net.URL(TUNNEL_INFO_HOST).openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val text = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            conn.inputStream.close()
            if (text.startsWith("https://") && text.contains(".trycloudflare.com")) url = text
        } catch (t: Throwable) {
        }
        if (url != null) {
            cachedTunnel = url
            lastTunnelFetch = now
        }
        return cachedTunnel
    }

    /** Hosts a tentar, na ordem: configurado/túnel primeiro, depois LAN e localhost. */
    fun serverCandidates(context: Context): List<String> {
        val out = ArrayList<String>(3)
        when (serverMode(context)) {
            "online" -> onlineServer(context)?.let { if (!out.contains(it)) out.add(it) }
            "custom" -> serverBase(context).let { if (it.isNotEmpty() && !out.contains(it)) out.add(it) }
        }
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

    fun setSpatial3d(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("spatial_3d", value).apply()
    }

    fun spatial3d(context: Context): Boolean =
        prefs(context).getBoolean("spatial_3d", false)

    fun setSpatial3dDepth(context: Context, value: Int) {
        prefs(context).edit().putInt("spatial_3d_depth", value).apply()
    }

    fun spatial3dDepth(context: Context): Int =
        prefs(context).getInt("spatial_3d_depth", 60).coerceIn(0, 100)

    fun setSurround(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("spatial_surround", value).apply()
    }

    fun surround(context: Context): Boolean =
        prefs(context).getBoolean("spatial_surround", false)

    fun setSurroundIntensity(context: Context, value: Int) {
        prefs(context).edit().putInt("spatial_surround_intensity", value).apply()
    }

    fun surroundIntensity(context: Context): Int =
        prefs(context).getInt("spatial_surround_intensity", 50).coerceIn(0, 100)

    fun setHotword(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("hotword", value).apply()
    }

    fun hotword(context: Context): Boolean =
        prefs(context).getBoolean("hotword", false)

    fun setAudioQuality(context: Context, value: String) {
        prefs(context).edit().putString("audio_quality", value).apply()
    }

    fun audioQuality(context: Context): String =
        prefs(context).getString("audio_quality", QUALITY_DEFAULT) ?: QUALITY_DEFAULT

    fun danceSpeed(context: Context): Float =
        if (audioQuality(context) == QUALITY_DANCE) DANCE_SPEED else 1f

    fun dancePitch(context: Context): Float =
        if (audioQuality(context) == QUALITY_DANCE) DANCE_PITCH else 1f

    /**
     * F2 — velocidade escolhida pelo usuário, só para vídeo/stream.
     *
     * Fica em `Settings` porque a tela de vídeo pode ser fechada e reaberta a qualquer
     * momento: sem persistir, o botão marcava 1,5x e o vídeo voltava a 1x assim que a
     * fila mudasse de faixa.
     */
    fun videoSpeed(context: Context): Float =
        prefs(context).getFloat("video_speed", 1f).coerceIn(0.5f, 2.0f)

    fun setVideoSpeed(context: Context, value: Float) {
        prefs(context).edit().putFloat("video_speed", value.coerceIn(0.5f, 2.0f)).apply()
    }

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

    fun alarmHour(context: Context): Int = prefs(context).getInt(KEY_ALARM_HOUR, -1)

    fun alarmMinute(context: Context): Int = prefs(context).getInt(KEY_ALARM_MINUTE, 0)

    fun alarmAmbient(context: Context): String =
        prefs(context).getString(KEY_ALARM_AMBIENT, "") ?: ""

    fun setAlarm(context: Context, hour: Int, minute: Int, ambient: String?) {
        prefs(context).edit()
            .putInt(KEY_ALARM_HOUR, hour)
            .putInt(KEY_ALARM_MINUTE, minute)
            .putString(KEY_ALARM_AMBIENT, ambient ?: "")
            .apply()
    }

    fun clearAlarm(context: Context) {
        prefs(context).edit()
            .putInt(KEY_ALARM_HOUR, -1)
            .putInt(KEY_ALARM_MINUTE, 0)
            .putString(KEY_ALARM_AMBIENT, "")
            .apply()
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

    fun setMasculineAvatar(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("avatar_masculino", value).apply()
    }

    fun masculineAvatar(context: Context): Boolean =
        prefs(context).getBoolean("avatar_masculino", false)

    /**
     * Qual dos tres avatares esta em uso.
     *
     * Foi um boolean so (virgem/Victor) e virou um id de 0 a 2 por causa da Vera. O
     * `avatar_masculino` antigo continua sendo lido/escrito no mesmo lugar por compat, e
     * a Vera entra como um valor novo do id -- quem ja tinha o Victor como masculino
     * continua com o Victor, sem migracao.
     */
    fun avatarStyle(context: Context): Int = prefs(context).getInt("avatar_style", -1).let { stored ->
        if (stored >= 0 && stored <= 2) {
            stored
        } else {
            // Sem id gravado: deriva do boolean legado.
            if (prefs(context).getBoolean("avatar_masculino", false)) VICTOR else VIRGIN
        }
    }

    fun setAvatarStyle(context: Context, value: Int) {
        val safe = value.coerceIn(VIRGIN, VERA)
        prefs(context).edit()
            .putInt("avatar_style", safe)
            // Mantem o flag antigo coerente, para o caso de algo legado ainda o leia.
            .putBoolean("avatar_masculino", safe == VICTOR)
            .apply()
    }

    fun assistantName(context: Context): String =
        context.getString(
            when (avatarStyle(context)) {
                VICTOR -> R.string.dj_voice_name_male
                VERA -> R.string.dj_voice_name_vera
                else -> R.string.dj_voice_name
            }
        )

    fun setDjRadio(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("dj_radio", value).apply()
    }

    fun djRadio(context: Context): Boolean =
        prefs(context).getBoolean("dj_radio", true)

    fun setTamiRadio(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("tami_radio", value).apply()
    }

    fun tamiRadio(context: Context): Boolean =
        prefs(context).getBoolean("tami_radio", false)

    fun setRecToken(context: Context, value: String) {
        prefs(context).edit().putString("rec_token", value.trim()).apply()
    }

    fun recToken(context: Context): String =
        prefs(context).getString("rec_token", "") ?: ""

    fun setAdminToken(context: Context, value: String) = setSecret(context, "admin_token", value)

    fun adminToken(context: Context): String =
        secret(context, "admin_token")

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

    /**
     * Instância do PeerTube usada na busca de vídeo (F2).
     *
     * Federado não é sinônimo de permanente: instância pública muda de nome, cai ou começa a
     * responder 403, e quem busca vídeo do PeerTube é justamente quem quer o link direto que
     * o YouTube esconde. Por isso é configurável, e o valor é normalizado na gravação para
     * não depender de o usuário digitar o esquema.
     */
    fun peerTubeInstance(context: Context): String =
        com.pulsa.player.data.PeerTube.normalizeInstance(
            prefs(context).getString("peertube_instance", "").orEmpty()
        )

    fun setPeerTubeInstance(context: Context, value: String) {
        prefs(context).edit()
            .putString("peertube_instance", com.pulsa.player.data.PeerTube.normalizeInstance(value))
            .apply()
    }

    fun setResumeOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("resume_on", value).apply()
    }

    fun resumeOn(context: Context): Boolean =
        prefs(context).getBoolean("resume_on", true)

    /**
     * F2 · Download Center — só baixa em rede não medida (Wi‑Fi/cabo).
     *
     * Padrão `true`: baixar um vídeo de centenas de MB no 4G sem avisar é o jeito mais rápido
     * de a primeira experiência do Download Center virar uma conta de dados. Quem quiser baixar
     * pelos dados desliga aqui.
     */
    fun setDownloadWifiOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("download_wifi_only", value).apply()
    }

    fun downloadWifiOnly(context: Context): Boolean =
        prefs(context).getBoolean("download_wifi_only", true)

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

    /**
     * Fila salva para atravessar morte de processo e atualização do app.
     *
     * O [resumeState] acima guarda só UMA música, e nada no boot a devolvia: o restore
     * automático era exclusivo do rádio. Numa atualização o Android mata o processo e o
     * `onDestroy` não chegava a rodar, então a fila inteira se perdia.
     *
     * Guarda as chaves em JSON, na ordem, com o índice e a posição. A faixa continua vindo
     * do MediaStore no restore — a chave é a referência, o resto é reconstruído, o que
     * sobrevive bem a uma tag do app ter mudado de id entre versões.
     *
     * F2b: o `keys` é `List<String>` (chave tipada, ver [QueueKey]) e não mais `List<Long>`,
     * porque o MediaStore numera áudio e vídeo em coleções separadas e o número sozinho não
     * diz de que mídia o item é. A preference continua se chamando `queue_ids` para não
     * invalidar o que já está gravado — o leitor aceita o número solto como sendo áudio.
     */
    fun setQueueState(context: Context, keys: List<String>, index: Int, positionMs: Long) {
        if (keys.isEmpty()) {
            clearQueueState(context)
            return
        }
        prefs(context).edit()
            .putString("queue_ids", org.json.JSONArray(keys).toString())
            .putInt("queue_index", index)
            .putLong("queue_position", positionMs)
            .putLong("queue_saved_at", System.currentTimeMillis())
            .apply()
    }

    /**
     * F2b — chaves (`a:42`, `v:7`) na ordem salva, o índice da faixa atual e a posição, em ms.
     *
     * A chave tipada substituiu o `id` solto porque o MediaStore numera áudio e vídeo em
     * coleções separadas: só o número não diz de que mídia o item é. A compatibilidade com o
     * formato antigo fica no leitor — um `JSONArray` de números ainda volta, tudo como áudio,
     * que é o que era.
     */
    fun queueState(context: Context): Triple<List<String>, Int, Long>? {
        val raw = prefs(context).getString("queue_ids", "") ?: ""
        if (raw.isBlank()) return null
        val keys = runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).mapNotNull { QueueKey.decode(arr.optString(it)) }
        }.getOrNull()
        if (keys.isNullOrEmpty()) return null
        return Triple(
            keys,
            prefs(context).getInt("queue_index", 0),
            prefs(context).getLong("queue_position", 0L)
        )
    }

    /**
     * Fila universal — o que o MediaStore não sabe sobre rádio e stream.
     *
     * A chave de rádio/stream é a URL (ver [QueueKey]), mas ela não carrega título nem
     * artista. Como esses itens não têm linha no MediaStore para o restore reconstruir, o
     * texto vai à parte, indexado pela própria chave. É um `JSONObject` de `chave -> {t,a}`;
     * assim os dois elementos que faltam voltam junto com a fila e uma URL estranha não
     * precisa caber em nenhum formato de chave.
     */
    fun setQueueExtras(context: Context, json: String) {
        prefs(context).edit().putString("queue_extras", json).apply()
    }

    fun queueExtras(context: Context): String =
        prefs(context).getString("queue_extras", "") ?: ""

    fun clearQueueState(context: Context) {
        prefs(context).edit()
            .remove("queue_ids")
            .remove("queue_index")
            .remove("queue_position")
            .remove("queue_saved_at")
            .remove("queue_extras")
            .apply()
    }

    // Rádio guardado para continuar tocando caso o processo renasça (E4). Só enquanto
    // estava de fato tocando/pausado por estado "tem som"; limpo no pause deliberado.
    fun setRadioResume(context: Context, url: String, title: String, genre: String) {
        prefs(context).edit()
            .putString("radio_resume_url", url)
            .putString("radio_resume_title", title)
            .putString("radio_resume_genre", genre)
            .apply()
    }

    fun radioResume(context: Context): Triple<String, String, String>? {
        val url = prefs(context).getString("radio_resume_url", "") ?: ""
        if (url.isBlank()) return null
        val title = prefs(context).getString("radio_resume_title", "") ?: ""
        val genre = prefs(context).getString("radio_resume_genre", "") ?: ""
        return Triple(url, title, genre)
    }

    fun clearRadioResume(context: Context) {
        prefs(context).edit()
            .remove("radio_resume_url")
            .remove("radio_resume_title")
            .remove("radio_resume_genre")
            .apply()
    }

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
