package com.pulsa.player.dj

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

object DjLearn {

    private val helpers = HashMap<String, Learner>()

    /** Marcado em toda mutação local; limpo após push bem-sucedido para a nuvem. */
    @Volatile
    var dirty: Boolean = false
        private set

    data class Stats(
        val plays: Int,
        val skips: Int,
        val listenMs: Long,
        val disliked: Boolean,
        val liked: Int
    )

    data class RewindResult(
        val plays: Int,
        val top: List<Pair<Long, Int>>,
        val unique: Int,
        val firstTs: Long?,
        val firstSongId: Long,
        val months: List<Pair<String, Int>>, // "mm/aaaa" → toques, dos 12 últimos meses
        val week: IntArray,                  // 7, dom..sáb
        val hours: IntArray,                 // 24
        val bestDay: Pair<String, Int>?,     // "dd/mm" → toques
        val nightOwl: Pair<Long, Int>?       // faixa com ≥50% dos toques entre 0h-6h
    )

    /** "Quantas vezes toquei essa": total + primeiro/último toque (ts em segundos). */
    data class PlayCount(val total: Int, val firstTs: Long?, val lastTs: Long?)

    class Learner(context: Context) :
        SQLiteOpenHelper(context.applicationContext, "dj_learn.db", null, 4) {

        private fun createStats(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS dj_stats (" +
                    "song_id INTEGER PRIMARY KEY, " +
                    "plays INTEGER NOT NULL DEFAULT 0, " +
                    "skips INTEGER NOT NULL DEFAULT 0, " +
                    "listen_ms INTEGER NOT NULL DEFAULT 0, " +
                    "disliked INTEGER NOT NULL DEFAULT 0, " +
                    "liked INTEGER NOT NULL DEFAULT 0)"
            )
        }

        /**
         * Log de reprodução. As três colunas extras (`kind`/`title`/`artist`) são o histórico
         * por tipo levado ao banco: `kind` diz se foi música, vídeo, podcast ou rádio e
         * `title`/`artist` guardam a legenda no momento do toque.
         *
         * Denormalizar o nome é de propósito: a linha do MediaStore de um vídeo pode sumir
         * (arquivo apagado, permissão revogada) e o histórico ficaria com ids órfãos — sem o
         * título gravado aqui, o "o que já tocou de vídeo" não teria o que falar.
         */
        private fun createPlayLog(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS play_log (" +
                    "song_id INTEGER NOT NULL, " +
                    "ts INTEGER NOT NULL, " +
                    "kind TEXT NOT NULL DEFAULT 'music', " +
                    "title TEXT NOT NULL DEFAULT '', " +
                    "artist TEXT NOT NULL DEFAULT '')"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_log_ts ON play_log(ts)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_log_kind ON play_log(kind, ts)")
        }

        override fun onCreate(db: SQLiteDatabase) {
            createStats(db)
            createPlayLog(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                runCatching {
                    db.execSQL("ALTER TABLE dj_stats ADD COLUMN liked INTEGER NOT NULL DEFAULT 0")
                }
            }
            if (oldVersion < 3) {
                createPlayLog(db)
            }
            if (oldVersion < 4) {
                // `play_log` do v3 só tinha song_id/ts: as músicas já registradas viram
                // `kind='music'` pelo DEFAULT, e o índice por (kind, ts) entra agora.
                runCatching {
                    db.execSQL("ALTER TABLE play_log ADD COLUMN kind TEXT NOT NULL DEFAULT 'music'")
                }
                runCatching {
                    db.execSQL("ALTER TABLE play_log ADD COLUMN title TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    db.execSQL("ALTER TABLE play_log ADD COLUMN artist TEXT NOT NULL DEFAULT ''")
                }
                runCatching {
                    db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_log_kind ON play_log(kind, ts)")
                }
            }
        }

        fun recordPlay(songId: Long) {
            bump(songId, "plays", 1)
            runCatching {
                writableDatabase.execSQL(
                    "INSERT INTO play_log (song_id, ts) VALUES (?, ?)",
                    arrayOf(songId, System.currentTimeMillis() / 1000L)
                )
            }
        }

        /**
         * Registra um toque no histórico **sem** tocar no `dj_stats` (aprendizado de música).
         *
         * É por aqui que passam vídeo, podcast, rádio e stream: a chave deles não é um
         * `song_id` da biblioteca, e um `recordPlay` normal misturaria esses ids com as
         * músicas nos contadores que o motor usa para recomendar — o mesmo número valendo
         * para um vídeo e uma música. Aqui só entra a linha do [play_log], com tipo e legenda.
         */
        fun recordTyped(songId: Long, kind: String, title: String, artist: String) {
            dirty = true
            runCatching {
                writableDatabase.execSQL(
                    "INSERT INTO play_log (song_id, ts, kind, title, artist) VALUES (?, ?, ?, ?, ?)",
                    arrayOf(songId, System.currentTimeMillis() / 1000L, kind, title, artist)
                )
            }
        }

        /**
         * Itens que já tocaram de um ou mais tipos em um dia (0 = hoje, -1 = ontem), do mais
         * recente para o mais antigo. Devolve `(artist, title)`, prontos para a locução.
         */
        fun playedByKind(nowSec: Long, kinds: List<String>, dayOffset: Int, limit: Int): List<Pair<String, String>> {
            if (kinds.isEmpty()) return emptyList()
            val result = ArrayList<Pair<String, String>>()
            val marks = kinds.joinToString(",") { "?" }
            runCatching {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = nowSec * 1000L
                cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis / 1000L
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis / 1000L
                val args = (listOf(start.toString(), end.toString()) + kinds + listOf(limit.toString()))
                    .toTypedArray()
                readableDatabase.rawQuery(
                    "SELECT artist, title, COUNT(*) AS n FROM play_log " +
                        "WHERE ts >= ? AND ts < ? AND kind IN ($marks) " +
                        "GROUP BY kind, song_id ORDER BY MAX(ts) DESC LIMIT ?",
                    args
                ).use { c ->
                    while (c.moveToNext()) {
                        result.add((c.getString(0) ?: "") to (c.getString(1) ?: ""))
                    }
                }
            }
            return result
        }

        fun heatmap(nowSec: Long, days: Int): IntArray {
            val out = IntArray(168)
            runCatching {
                val from = nowSec - days * 86400L
                val cal = java.util.Calendar.getInstance()
                readableDatabase.rawQuery(
                    "SELECT ts FROM play_log WHERE ts >= ?",
                    arrayOf(from.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        cal.timeInMillis = c.getLong(0) * 1000L
                        val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                        out[dow * 24 + hour]++
                    }
                }
            }
            return out
        }

        /** Agregados do "Pulsa Rewind" para um período (days=Int.MAX_VALUE = de sempre). */
        fun rewind(nowSec: Long, days: Int, limit: Int): RewindResult {
            val from = if (days == Int.MAX_VALUE) 0L else nowSec - days * 86400L
            val count = mutableMapOf<Long, Int>()
            val hourOf = IntArray(24)
            val weekOf = IntArray(7)
            val dayOf = mutableMapOf<String, Int>()
            val monthOf = mutableMapOf<String, Int>()
            var firstTs: Long? = null
            var firstSongId = -1L
            var total = 0
            runCatching {
                val cal = java.util.Calendar.getInstance()
                readableDatabase.rawQuery(
                    "SELECT song_id, ts FROM play_log WHERE ts >= ?",
                    arrayOf(from.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val ts = c.getLong(1)
                        count[id] = (count[id] ?: 0) + 1
                        total++
                        if (firstTs == null || ts < firstTs!!) {
                            firstTs = ts
                            firstSongId = id
                        }
                        cal.timeInMillis = ts * 1000L
                        hourOf[cal.get(java.util.Calendar.HOUR_OF_DAY)]++
                        weekOf[(cal.get(java.util.Calendar.DAY_OF_WEEK) + 6) % 7]++
                        val dd = cal.get(java.util.Calendar.DAY_OF_MONTH)
                        val mm = cal.get(java.util.Calendar.MONTH) + 1
                        dayOf["%02d/%02d".format(dd, mm)] = (dayOf["%02d/%02d".format(dd, mm)] ?: 0) + 1
                        monthOf["%02d/%04d".format(mm, cal.get(java.util.Calendar.YEAR))] =
                            (monthOf["%02d/%04d".format(mm, cal.get(java.util.Calendar.YEAR))] ?: 0) + 1
                    }
                }
            }
            val top = count.toList().sortedByDescending { it.second }.take(limit)
            val bestDay = dayOf.maxByOrNull { it.value }?.toPair()
            var nightOwl: Pair<Long, Int>? = null
            runCatching {
                for ((id, plays) in top) {
                    var night = 0
                    readableDatabase.rawQuery(
                        "SELECT ts FROM play_log WHERE song_id = ? AND ts >= ?",
                        arrayOf(id.toString(), from.toString())
                    ).use { c ->
                        val cal = java.util.Calendar.getInstance()
                        while (c.moveToNext()) {
                            cal.timeInMillis = c.getLong(0) * 1000L
                            val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
                            if (h < 6) night++
                        }
                    }
                    if (plays > 0 && night.toDouble() / plays >= 0.5) {
                        val cur = nightOwl
                        if (cur == null || plays > cur.second) nightOwl = id to plays
                    }
                }
            }
            val months = monthOf.toList().sortedBy { it.first }.takeLast(12)
            return RewindResult(total, top, count.size, firstTs, firstSongId, months, weekOf, hourOf, bestDay, nightOwl)
        }

        fun topSongs(nowSec: Long, days: Int, limit: Int): List<Pair<Long, Int>> {
            val result = ArrayList<Pair<Long, Int>>()
            runCatching {
                val from = nowSec - days * 86400L
                readableDatabase.rawQuery(
                    "SELECT song_id, COUNT(*) AS n FROM play_log WHERE ts >= ? " +
                        "GROUP BY song_id ORDER BY n DESC LIMIT ?",
                    arrayOf(from.toString(), limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        result.add(c.getLong(0) to c.getInt(1))
                    }
                }
            }
            return result
        }

        /** Músicas tocadas em um dia específico (dayOffset 0 = hoje, -1 = ontem). */
        fun playedOnDay(nowSec: Long, dayOffset: Int, limit: Int): List<Pair<Long, Int>> {
            val result = ArrayList<Pair<Long, Int>>()
            runCatching {
                val cal = java.util.Calendar.getInstance()
                cal.timeInMillis = nowSec * 1000L
                cal.add(java.util.Calendar.DAY_OF_YEAR, dayOffset)
                cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
                cal.set(java.util.Calendar.MINUTE, 0)
                cal.set(java.util.Calendar.SECOND, 0)
                cal.set(java.util.Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis / 1000L
                cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
                val end = cal.timeInMillis / 1000L
                readableDatabase.rawQuery(
                    "SELECT song_id, COUNT(*) AS n FROM play_log WHERE ts >= ? AND ts < ? " +
                        "GROUP BY song_id ORDER BY MAX(ts) DESC LIMIT ?",
                    arrayOf(start.toString(), end.toString(), limit.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        result.add(c.getLong(0) to c.getInt(1))
                    }
                }
            }
            return result
        }

        /**
         * "Quantas vezes toquei essa": total de toques, primeiro e último, do play_log.
         * `null` quando a faixa nunca tocou — quem fala decide o que responder.
         */
        fun playCount(songId: Long): PlayCount? {
            var total = 0
            var firstTs: Long? = null
            var lastTs: Long? = null
            runCatching {
                readableDatabase.rawQuery(
                    "SELECT COUNT(*), MIN(ts), MAX(ts) FROM play_log WHERE song_id = ?",
                    arrayOf(songId.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        total = c.getInt(0)
                        if (!c.isNull(1)) firstTs = c.getLong(1)
                        if (!c.isNull(2)) lastTs = c.getLong(2)
                    }
                }
            }
            if (total <= 0) return null
            return PlayCount(total, firstTs, lastTs)
        }

        fun recordSkip(songId: Long) {
            bump(songId, "skips", 1)
        }

        fun recordDislike(songId: Long) {
            bump(songId, "disliked", 1)
            bump(songId, "skips", 3)
        }

        fun recordLiked(songId: Long) {
            bump(songId, "liked", 1)
        }

        fun recordListenMs(songId: Long, ms: Long) {
            if (ms <= 0L) return
            dirty = true
            runCatching {
                val db = writableDatabase
                db.execSQL(
                    "INSERT OR IGNORE INTO dj_stats (song_id) VALUES (?)",
                    arrayOf(songId)
                )
                db.execSQL(
                    "UPDATE dj_stats SET listen_ms = listen_ms + ? WHERE song_id = ?",
                    arrayOf(ms, songId)
                )
            }
        }

        private fun bump(songId: Long, column: String, by: Int) {
            dirty = true
            runCatching {
                val db = writableDatabase
                db.execSQL(
                    "INSERT OR IGNORE INTO dj_stats (song_id) VALUES (?)",
                    arrayOf(songId)
                )
                db.execSQL(
                    "UPDATE dj_stats SET $column = $column + ? WHERE song_id = ?",
                    arrayOf(by, songId)
                )
            }
        }

        fun stats(songId: Long): Stats? = runCatching {
            val db = readableDatabase
            db.rawQuery(
                "SELECT plays, skips, listen_ms, disliked, liked FROM dj_stats WHERE song_id = ?",
                arrayOf(songId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    Stats(
                        c.getInt(0), c.getInt(1), c.getLong(2), c.getInt(3) > 0, c.getInt(4)
                    )
                } else null
            }
        }.getOrNull()

        fun learn(): DjEngine.Learn {
            val disliked = mutableSetOf<Long>()
            val skipCount = mutableMapOf<Long, Int>()
            val plays = mutableMapOf<Long, Int>()
            val liked = mutableMapOf<Long, Int>()
            val avoided = mutableSetOf<Long>()
            runCatching {
                val db = readableDatabase
                db.rawQuery(
                    "SELECT song_id, plays, skips, disliked, liked FROM dj_stats", null
                )
                    .use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            val playsHere = c.getInt(1)
                            val skipsHere = c.getInt(2)
                            skipCount[id] = skipsHere
                            plays[id] = playsHere
                            liked[id] = c.getInt(4)
                            if (c.getInt(3) > 0) disliked.add(id)
                            else if (skipsHere >= 3 && skipsHere >= playsHere) avoided.add(id)
                        }
                    }
            }
            return DjEngine.Learn(disliked, skipCount, plays, liked, avoided)
        }

        /** Snapshot completo dos contadores, para espelhar no servidor. */
        fun snapshot(): JSONObject {
            val arr = JSONArray()
            runCatching {
                val db = readableDatabase
                db.rawQuery(
                    "SELECT song_id, plays, skips, listen_ms, disliked, liked FROM dj_stats",
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("songId", c.getLong(0))
                            put("plays", c.getInt(1))
                            put("skips", c.getInt(2))
                            put("listenMs", c.getLong(3))
                            put("disliked", c.getInt(4) > 0)
                            put("liked", c.getInt(5))
                        })
                    }
                }
            }
            return JSONObject().apply { put("songs", arr) }
        }

        /** Mescla snapshot remoto no banco local (max por contador; disliked vira flag). */
        fun mergeRemote(json: JSONObject): Boolean {
            val songs = json.optJSONArray("songs") ?: return false
            var changed = false
            runCatching {
                val db = writableDatabase
                for (i in 0 until songs.length()) {
                    val o = songs.optJSONObject(i) ?: continue
                    val id = o.optLong("songId")
                    if (id <= 0L) continue
                    val rPlays = o.optInt("plays")
                    val rSkips = o.optInt("skips")
                    val rListen = o.optLong("listenMs")
                    val rDisliked = o.optBoolean("disliked")
                    val rLiked = o.optInt("liked")
                    val local = stats(id)
                    val lPlays = local?.plays ?: 0
                    val lSkips = local?.skips ?: 0
                    val lListen = local?.listenMs ?: 0L
                    val lDisliked = local?.disliked ?: false
                    val lLiked = local?.liked ?: 0
                    if (local != null && rPlays <= lPlays && rSkips <= lSkips &&
                        rListen <= lListen && (!rDisliked || lDisliked) && rLiked <= lLiked
                    ) continue
                    db.execSQL(
                        "INSERT OR IGNORE INTO dj_stats (song_id) VALUES (?)",
                        arrayOf(id)
                    )
                    db.execSQL(
                        "UPDATE dj_stats SET plays = ?, skips = ?, listen_ms = ?, " +
                            "disliked = ?, liked = ? WHERE song_id = ?",
                        arrayOf(
                            maxOf(rPlays, lPlays),
                            maxOf(rSkips, lSkips),
                            maxOf(rListen, lListen),
                            if (rDisliked || lDisliked) 1 else 0,
                            maxOf(rLiked, lLiked),
                            id
                        )
                    )
                    changed = true
                }
            }
            return changed
        }
    }

    private fun learner(context: Context): Learner {
        val key = context.applicationContext.packageName
        return helpers.getOrPut(key) { Learner(context.applicationContext) }
    }

    fun recordPlay(context: Context, songId: Long) = learner(context).recordPlay(songId)

    fun recordSkip(context: Context, songId: Long) = learner(context).recordSkip(songId)

    fun recordDislike(context: Context, songId: Long) = learner(context).recordDislike(songId)

    fun recordLiked(context: Context, songId: Long) = learner(context).recordLiked(songId)

    fun recordListenMs(context: Context, songId: Long, ms: Long) =
        learner(context).recordListenMs(songId, ms)

    fun snapshot(context: Context): JSONObject = learner(context).snapshot()

    fun mergeRemote(context: Context, json: JSONObject): Boolean =
        learner(context).mergeRemote(json)

    /** Limpa a flag dirty após push bem-sucedido. */
    fun markSynced() {
        dirty = false
    }

    fun learn(context: Context): DjEngine.Learn = learner(context).learn()

    fun stats(context: Context, songId: Long): Stats? = learner(context).stats(songId)

    fun heatmap(context: Context): IntArray =
        learner(context).heatmap(System.currentTimeMillis() / 1000L, 30)

    fun topSongs(context: Context, days: Int, limit: Int): List<Pair<Long, Int>> =
        learner(context).topSongs(System.currentTimeMillis() / 1000L, days, limit)

    fun playedOnDay(context: Context, dayOffset: Int, limit: Int): List<Pair<Long, Int>> =
        learner(context).playedOnDay(System.currentTimeMillis() / 1000L, dayOffset, limit)

    fun playCount(context: Context, songId: Long): PlayCount? =
        learner(context).playCount(songId)

    fun rewind(context: Context, days: Int, limit: Int): RewindResult =
        learner(context).rewind(System.currentTimeMillis() / 1000L, days, limit)

    /** Último toque (ts, em segundos) por música, de todas as músicas já vistas. */
    fun lastPlayedMap(context: Context): Map<Long, Long> {
        val out = HashMap<Long, Long>()
        runCatching {
            learner(context).readableDatabase.rawQuery(
                "SELECT song_id, MAX(ts) FROM play_log GROUP BY song_id", null
            ).use { c ->
                while (c.moveToNext()) out[c.getLong(0)] = c.getLong(1)
            }
        }
        return out
    }

    /**
     * Chave de histórico de uma mídia que não é música — negativa para nunca colidir com os
     * `song_id` positivos da biblioteca. `null` quando o item não tem identidade estável.
     */
    fun typedKey(song: com.pulsa.player.model.Song): Long? = when {
        song.isPodcast -> song.podcastId?.let { -it }
        song.isVideo -> song.videoId?.let { -it }
        song.isRadio -> song.radioUrl?.let { -it.hashCode().toLong() }
        song.isStream -> song.streamUrl?.let { -it.hashCode().toLong() }
        else -> null
    }

    /** Tipo do [play_log] para o histórico por tipo. Música é `song_id` positivo. */
    fun kindOf(song: com.pulsa.player.model.Song): String = when {
        song.isPodcast -> "podcast"
        song.isRadio -> "radio"
        song.isVideo || song.isStream -> "video"
        else -> "music"
    }

    /** Grava o toque de uma mídia não-música (vídeo/podcast/rádio/stream) no histórico. */
    fun recordTyped(context: Context, song: com.pulsa.player.model.Song) {
        val key = typedKey(song) ?: return
        learner(context).recordTyped(key, kindOf(song), song.title, song.artist)
    }

    /** Tipos do [play_log] que uma consulta de histórico ("video"/"podcast"/…) abrange. */
    fun kindsFor(type: String): List<String> = when (type) {
        "video" -> listOf("video")
        "podcast" -> listOf("podcast")
        "music" -> listOf("music")
        "radio" -> listOf("radio")
        else -> listOf("music", "video", "podcast", "radio")
    }

    /** Itens que já tocaram hoje, por tipo, prontos para a locução. */
    fun playedByKind(context: Context, type: String, dayOffset: Int, limit: Int): List<Pair<String, String>> =
        learner(context).playedByKind(
            System.currentTimeMillis() / 1000L, kindsFor(type), dayOffset, limit
        )
}
