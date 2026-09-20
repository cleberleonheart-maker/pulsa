package com.pulsa.player.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.pulsa.player.model.Playlist
import com.pulsa.player.model.Song
import com.pulsa.player.model.SongMeta

class PlaylistDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "pulsa.db", null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE playlists (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "created INTEGER NOT NULL DEFAULT 0, " +
                "auto_add INTEGER NOT NULL DEFAULT 0, " +
                "system INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE playlist_songs (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "playlist_id INTEGER NOT NULL, " +
                "song_id INTEGER NOT NULL, " +
                "path TEXT, " +
                "title TEXT, " +
                "artist TEXT, " +
                "album TEXT, " +
                "album_id INTEGER, " +
                "duration INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE favorites (" +
                "song_id INTEGER PRIMARY KEY, " +
                "path TEXT, " +
                "title TEXT, " +
                "artist TEXT, " +
                "album TEXT, " +
                "album_id INTEGER, " +
                "duration INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE song_meta (" +
                "song_id INTEGER PRIMARY KEY, " +
                "title TEXT, " +
                "artist TEXT, " +
                "album TEXT)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN auto_add INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS favorites (" +
                    "song_id INTEGER PRIMARY KEY, " +
                    "path TEXT, " +
                    "title TEXT, " +
                    "artist TEXT, " +
                    "album TEXT, " +
                    "album_id INTEGER, " +
                    "duration INTEGER)"
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE playlists ADD COLUMN system INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 5) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS song_meta (" +
                    "song_id INTEGER PRIMARY KEY, " +
                    "title TEXT, " +
                    "artist TEXT, " +
                    "album TEXT)"
            )
        }
    }

    fun isSystem(playlistId: Long): Boolean {
        val db = readableDatabase
        db.query(
            "playlists",
            arrayOf("system"),
            "_id = ?",
            arrayOf(playlistId.toString()),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0) == 1
        }
        return false
    }

    fun ensureSpecialPlaylists(context: Context) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existsSystem = db.rawQuery("SELECT COUNT(*) FROM playlists WHERE system = 1", null)
            val hasSystem = existsSystem.use { it.moveToFirst() && it.getInt(0) > 0 }
            if (!hasSystem) {
                val names = arrayOf(
                    context.getString(com.pulsa.player.R.string.gospel_playlist),
                    context.getString(com.pulsa.player.R.string.variadas_playlist),
                    context.getString(com.pulsa.player.R.string.smart_new_playlist),
                    context.getString(com.pulsa.player.R.string.smart_classics_playlist)
                )
                names.forEach { name ->
                    val values = ContentValues().apply {
                        put("name", name)
                        put("created", System.currentTimeMillis())
                        put("auto_add", 1)
                        put("system", 1)
                    }
                    db.insert("playlists", null, values)
                }
            } else {
                db.rawQuery("SELECT _id FROM playlists WHERE system = 1 AND auto_add = 0", null)
                    .use { c ->
                        while (c.moveToNext()) {
                            val values = ContentValues().apply { put("auto_add", 1) }
                            db.update("playlists", values, "_id = ?", arrayOf(c.getLong(0).toString()))
                        }
                    }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun syncAutoSongs(context: Context, songs: List<Song>) {
        val db = readableDatabase
        val targets = mutableListOf<Pair<Long, String>>()
        db.rawQuery(
            "SELECT _id, name FROM playlists WHERE auto_add = 1",
            null
        ).use { c ->
            while (c.moveToNext()) {
                targets += c.getLong(0) to (c.getString(1) ?: "")
            }
        }
        if (targets.isEmpty()) return
        val gospelName = context.getString(com.pulsa.player.R.string.gospel_playlist)
        val variadasName = context.getString(com.pulsa.player.R.string.variadas_playlist)
        val smartNewName = context.getString(com.pulsa.player.R.string.smart_new_playlist)
        val smartClassicsName = context.getString(com.pulsa.player.R.string.smart_classics_playlist)
        val isGospel = Library.isGospel(context, songs)
        targets.forEach { (id, name) ->
            val filtered = when (name) {
                gospelName -> songs.filterIndexed { i, _ -> isGospel[i] }
                variadasName -> songs.filterIndexed { i, _ -> !isGospel[i] }
                smartNewName -> songs.filter { it.year >= 2020 }
                smartClassicsName -> songs.filter { it.year > 0 && it.year < 2000 }
                else -> songs
            }
            addAllMissingSongs(id, filtered)
        }
    }

    fun syncAutoPlaylist(context: Context, playlistId: Long, songs: List<Song>) {
        val db = readableDatabase
        var auto = false
        var name = ""
        db.query(
            "playlists",
            arrayOf("auto_add", "name"),
            "_id = ?",
            arrayOf(playlistId.toString()),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) {
                auto = c.getInt(0) == 1
                name = c.getString(1) ?: ""
            }
        }
        if (!auto) return
        val gospelName = context.getString(com.pulsa.player.R.string.gospel_playlist)
        val variadasName = context.getString(com.pulsa.player.R.string.variadas_playlist)
        val isGospel = Library.isGospel(context, songs)
        val filtered = when (name) {
            gospelName -> songs.filterIndexed { i, _ -> isGospel[i] }
            variadasName -> songs.filterIndexed { i, _ -> !isGospel[i] }
            else -> songs
        }
        addAllMissingSongs(playlistId, filtered)
    }

    fun playlists(): List<Playlist> {
        val out = mutableListOf<Playlist>()
        val db = readableDatabase
        db.rawQuery(
            "SELECT p._id, p.name, COUNT(ps._id), p.auto_add, p.system FROM playlists p " +
                "LEFT JOIN playlist_songs ps ON ps.playlist_id = p._id " +
                "GROUP BY p._id ORDER BY p.system DESC, p.created ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out += Playlist(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "",
                    count = c.getInt(2),
                    autoAdd = c.getInt(3) == 1,
                    system = c.getInt(4) == 1
                )
            }
        }
        return out
    }

    fun isAutoAdd(playlistId: Long): Boolean {
        val db = readableDatabase
        db.query(
            "playlists",
            arrayOf("auto_add"),
            "_id = ?",
            arrayOf(playlistId.toString()),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return c.getInt(0) == 1
        }
        return false
    }

    fun setAutoAdd(playlistId: Long, value: Boolean) {
        val values = ContentValues().apply { put("auto_add", if (value) 1 else 0) }
        writableDatabase.update("playlists", values, "_id = ?", arrayOf(playlistId.toString()))
    }

    fun addAllMissingSongs(playlistId: Long, songs: List<Song>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            songs.forEach { addSong(playlistId, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun isFavorite(songId: Long): Boolean {
        val db = readableDatabase
        db.query(
            "favorites",
            arrayOf("song_id"),
            "song_id = ?",
            arrayOf(songId.toString()),
            null, null, null, "1"
        ).use { c ->
            return c.moveToFirst()
        }
    }

    fun setFavorite(song: Song, value: Boolean) {
        val db = writableDatabase
        if (value) {
            val values = ContentValues().apply {
                put("song_id", song.id)
                put("path", song.path)
                put("title", song.title)
                put("artist", song.artist)
                put("album", song.album)
                put("album_id", song.albumId)
                put("duration", song.durationMs)
            }
            db.insertWithOnConflict("favorites", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        } else {
            db.delete("favorites", "song_id = ?", arrayOf(song.id.toString()))
        }
    }

    fun favorites(): List<Song> {
        val out = mutableListOf<Song>()
        val db = readableDatabase
        db.query(
            "favorites",
            arrayOf("song_id", "path", "title", "artist", "album", "album_id", "duration"),
            null, null, null, null, "song_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += Song(
                    id = c.getLong(0),
                    title = c.getString(2) ?: "",
                    artist = c.getString(3) ?: "Artista desconhecido",
                    album = c.getString(4) ?: "Desconhecido",
                    albumId = c.getLong(5),
                    durationMs = c.getLong(6),
                    path = c.getString(1) ?: "",
                    year = 0
                )
            }
        }
        return out
    }

    fun removeFavorite(songId: Long) {
        writableDatabase.delete("favorites", "song_id = ?", arrayOf(songId.toString()))
    }

    fun createPlaylist(name: String): Long {
        val values = ContentValues().apply {
            put("name", name.trim())
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("playlists", null, values)
    }

    fun renamePlaylist(id: Long, name: String) {
        val values = ContentValues().apply { put("name", name.trim()) }
        writableDatabase.update("playlists", values, "_id = ?", arrayOf(id.toString()))
    }

    fun deletePlaylist(id: Long) {
        val db = writableDatabase
        db.delete("playlist_songs", "playlist_id = ?", arrayOf(id.toString()))
        db.delete("playlists", "_id = ?", arrayOf(id.toString()))
    }

    fun addSong(playlistId: Long, song: Song): Boolean {
        val db = writableDatabase
        db.query(
            "playlist_songs",
            arrayOf("_id"),
            "playlist_id = ? AND song_id = ?",
            arrayOf(playlistId.toString(), song.id.toString()),
            null, null, null, "1"
        ).use { c ->
            if (c.moveToFirst()) return false
        }
        val values = ContentValues().apply {
            put("playlist_id", playlistId)
            put("song_id", song.id)
            put("path", song.path)
            put("title", song.title)
            put("artist", song.artist)
            put("album", song.album)
            put("album_id", song.albumId)
            put("duration", song.durationMs)
        }
        db.insert("playlist_songs", null, values)
        return true
    }

    fun removeSong(playlistId: Long, songId: Long) {
        writableDatabase.delete(
            "playlist_songs",
            "playlist_id = ? AND song_id = ?",
            arrayOf(playlistId.toString(), songId.toString())
        )
    }

    fun removeSongFromAll(songId: Long) {
        writableDatabase.delete("playlist_songs", "song_id = ?", arrayOf(songId.toString()))
    }

    fun updateSongMeta(songId: Long, title: String, artist: String, album: String) {
        val values = ContentValues().apply {
            put("song_id", songId)
            put("title", title)
            put("artist", artist)
            put("album", album)
        }
        val db = writableDatabase
        db.update("favorites", values, "song_id = ?", arrayOf(songId.toString()))
        db.update("playlist_songs", values, "song_id = ?", arrayOf(songId.toString()))
        db.insertWithOnConflict("song_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun saveMetaOverride(songId: Long, title: String, artist: String, album: String) {
        val values = ContentValues().apply {
            put("song_id", songId)
            put("title", title)
            put("artist", artist)
            put("album", album)
        }
        writableDatabase.insertWithOnConflict("song_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clearMetaOverride(songId: Long) {
        writableDatabase.delete("song_meta", "song_id = ?", arrayOf(songId.toString()))
    }

    fun metaOverrides(): Map<Long, SongMeta> {
        val out = HashMap<Long, SongMeta>()
        readableDatabase.query(
            "song_meta",
            arrayOf("song_id", "title", "artist", "album"),
            null, null, null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                out[c.getLong(0)] = SongMeta(
                    songId = c.getLong(0),
                    title = c.getString(1) ?: "",
                    artist = c.getString(2) ?: "",
                    album = c.getString(3) ?: ""
                )
            }
        }
        return out
    }

    fun songMeta(songId: Long): SongMeta? = metaOverrides()[songId]

    fun songs(playlistId: Long): List<Song> {
        val out = mutableListOf<Song>()
        val db = readableDatabase
        db.query(
            "playlist_songs",
            arrayOf("song_id", "path", "title", "artist", "album", "album_id", "duration"),
            "playlist_id = ?",
            arrayOf(playlistId.toString()),
            null, null, "_id ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out += Song(
                    id = c.getLong(0),
                    title = c.getString(2) ?: "",
                    artist = c.getString(3) ?: "Artista desconhecido",
                    album = c.getString(4) ?: "Desconhecido",
                    albumId = c.getLong(5),
                    durationMs = c.getLong(6),
                    path = c.getString(1) ?: "",
                    year = 0
                )
            }
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: PlaylistDb? = null

        fun get(context: Context): PlaylistDb {
            return instance ?: synchronized(this) {
                instance ?: PlaylistDb(context.applicationContext).also { instance = it }
            }
        }
    }
}
