package com.pulsa.player.util

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object DjLearn {

    private val helpers = HashMap<String, Learner>()

    data class Stats(
        val plays: Int,
        val skips: Int,
        val listenMs: Long,
        val disliked: Boolean,
        val liked: Int
    )

    class Learner(context: Context) :
        SQLiteOpenHelper(context.applicationContext, "dj_learn.db", null, 3) {

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

        private fun createPlayLog(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS play_log (" +
                    "song_id INTEGER NOT NULL, " +
                    "ts INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_play_log_ts ON play_log(ts)")
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
            runCatching {
                val db = readableDatabase
                db.rawQuery(
                    "SELECT song_id, plays, skips, disliked, liked FROM dj_stats", null
                )
                    .use { c ->
                        while (c.moveToNext()) {
                            val id = c.getLong(0)
                            skipCount[id] = c.getInt(2)
                            plays[id] = c.getInt(1)
                            liked[id] = c.getInt(4)
                            if (c.getInt(3) > 0) disliked.add(id)
                        }
                    }
            }
            return DjEngine.Learn(disliked, skipCount, plays, liked)
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

    fun learn(context: Context): DjEngine.Learn = learner(context).learn()

    fun stats(context: Context, songId: Long): Stats? = learner(context).stats(songId)

    fun heatmap(context: Context): IntArray =
        learner(context).heatmap(System.currentTimeMillis() / 1000L, 30)

    fun topSongs(context: Context, days: Int, limit: Int): List<Pair<Long, Int>> =
        learner(context).topSongs(System.currentTimeMillis() / 1000L, days, limit)
}