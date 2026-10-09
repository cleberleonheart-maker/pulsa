package com.pulsa.player.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        FavoriteEntity::class,
        SongMetaEntity::class,
        PodcastFeedEntity::class,
        PodcastEpisodeEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class PulsaDatabase : RoomDatabase() {

    abstract fun playlistDao(): PlaylistDao
    abstract fun playlistSongDao(): PlaylistSongDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun songMetaDao(): SongMetaDao
    abstract fun podcastDao(): PodcastDao

    companion object {
        const val NAME = "pulsa.db"

        /**
         * 6 → 7 é a chegada do Room, e aqui é a parte que exige cuidado de verdade.
         *
         * A tentação é escrever uma migração vazia, porque as quatro tabelas têm as mesmas colunas.
         * **Isso quebraria o app em todo aparelho que já tem uso** — e só apareceria no aparelho,
         * depois de instalar. O Room valida o esquema na abertura (`onValidateSchema`) e a
         * comparação é estrita: para cada coluna ele compara nome, tipo, `notNull` e
         * `defaultValue`.
         *
         * O schema que o `SQLiteOpenHelper` antigo criou usava `_id INTEGER PRIMARY KEY
         * AUTOINCREMENT` **sem** `NOT NULL`, e é isso que o SQLite registra: `PRAGMA table_info`
         * devolvia `notnull=0`. Já o Room escreve `` `_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT
         * NULL ``, porque um `Long` não anulável em Kotlin é `NOT NULL` para ele. Mesma coluna,
         * `notNull` diferente — e o Room não abre.
         *
         * Daí a reconstrução: cria as tabelas no formato que o Room espera, copia os dados e
         * descarta as antigas. São quatro tabelas pequenas (playlists, favoritas e o que está
         * dentro delas), então a cópia é instantânea. O que **não** pode acontecer é perder as
         * playlists e as favoritas do usuário, que é o que um
         * `fallbackToDestructiveMigration()` resolveria em uma linha.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                rebuild(
                    db, "playlists",
                    create = "CREATE TABLE IF NOT EXISTS `playlists` (" +
                        "`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`created` INTEGER NOT NULL DEFAULT 0, " +
                        "`auto_add` INTEGER NOT NULL DEFAULT 0, " +
                        "`system` INTEGER NOT NULL DEFAULT 0)",
                    columns = "`_id`, `name`, `created`, `auto_add`, `system`"
                )
                rebuild(
                    db, "playlist_songs",
                    create = "CREATE TABLE IF NOT EXISTS `playlist_songs` (" +
                        "`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playlist_id` INTEGER NOT NULL, " +
                        "`song_id` INTEGER NOT NULL, " +
                        "`path` TEXT, `title` TEXT, `artist` TEXT, `album` TEXT, " +
                        "`album_id` INTEGER, `duration` INTEGER)",
                    columns = "`_id`, `playlist_id`, `song_id`, `path`, `title`, `artist`, " +
                        "`album`, `album_id`, `duration`"
                )
                rebuild(
                    db, "favorites",
                    create = "CREATE TABLE IF NOT EXISTS `favorites` (" +
                        "`song_id` INTEGER NOT NULL, " +
                        "`path` TEXT, `title` TEXT, `artist` TEXT, `album` TEXT, " +
                        "`album_id` INTEGER, `duration` INTEGER, " +
                        "`liked_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`song_id`))",
                    columns = "`song_id`, `path`, `title`, `artist`, `album`, `album_id`, " +
                        "`duration`, `liked_at`"
                )
                rebuild(
                    db, "song_meta",
                    create = "CREATE TABLE IF NOT EXISTS `song_meta` (" +
                        "`song_id` INTEGER NOT NULL, " +
                        "`title` TEXT, `artist` TEXT, `album` TEXT, " +
                        "PRIMARY KEY(`song_id`))",
                    columns = "`song_id`, `title`, `artist`, `album`"
                )
            }

            private fun rebuild(
                db: SupportSQLiteDatabase,
                table: String,
                create: String,
                columns: String
            ) {
                val old = "${table}_v6"
                db.execSQL("ALTER TABLE `$table` RENAME TO `$old`")
                db.execSQL(create)
                db.execSQL("INSERT INTO `$table` ($columns) SELECT $columns FROM `$old`")
                db.execSQL("DROP TABLE `$old`")
            }
        }

        /**
         * 7 → 8 é o podcast (F3), e é a migração mais simples possível: duas tabelas novas.
         *
         * Nenhuma coluna existente muda e nenhuma linha é tocada. Os `DEFAULT` espelham as
         * entidades porque o Room compara `defaultValue` coluna a coluna na validação de
         * esquema — `TEXT` aqui contra `TEXT NOT NULL DEFAULT ''` na entidade faria o app não
         * abrir no aparelho, e só depois de instalar.
         *
         * O detalhe que costuma faltar é o `UNIQUE (podcast_id, guid)`. Sem ele o
         * `INSERT OR IGNORE` dos episódios vira `INSERT` e cada atualização do feed duplica a
         * lista inteira.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_feeds` (" +
                        "`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`feed_url` TEXT NOT NULL DEFAULT '', " +
                        "`title` TEXT NOT NULL DEFAULT '', " +
                        "`author` TEXT NOT NULL DEFAULT '', " +
                        "`description` TEXT NOT NULL DEFAULT '', " +
                        "`artwork_url` TEXT NOT NULL DEFAULT '', " +
                        "`last_fetched` INTEGER NOT NULL DEFAULT 0, " +
                        "`last_error` TEXT NOT NULL DEFAULT '', " +
                        "`subscribed` INTEGER NOT NULL DEFAULT 1)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `podcast_episodes` (" +
                        "`_id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`podcast_id` INTEGER NOT NULL, " +
                        "`guid` TEXT NOT NULL, " +
                        "`title` TEXT NOT NULL DEFAULT '', " +
                        "`description` TEXT NOT NULL DEFAULT '', " +
                        "`audio_url` TEXT NOT NULL, " +
                        "`duration_sec` INTEGER NOT NULL DEFAULT 0, " +
                        "`published_at` INTEGER NOT NULL DEFAULT 0, " +
                        "`artwork_url` TEXT NOT NULL DEFAULT '', " +
                        "`is_video` INTEGER NOT NULL DEFAULT 0, " +
                        "`video_page_url` TEXT NOT NULL DEFAULT '', " +
                        "`file_path` TEXT NOT NULL DEFAULT '', " +
                        "`played` INTEGER NOT NULL DEFAULT 0, " +
                        "`position_ms` INTEGER NOT NULL DEFAULT 0)"
                )
                // Tem de existir antes de qualquer `INSERT OR IGNORE` de feed: e o que faz o
                // `subscribe` devolver o id ja criado em vez de duplicar a assinatura.
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_feeds_feed_url` " +
                        "ON `podcast_feeds` (`feed_url`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_podcast_episodes_podcast_id_guid` " +
                        "ON `podcast_episodes` (`podcast_id`, `guid`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_podcast_episodes_podcast_id_played` " +
                        "ON `podcast_episodes` (`podcast_id`, `played`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_podcast_episodes_published_at` " +
                        "ON `podcast_episodes` (`published_at`)"
                )
            }
        }

        @Volatile
        private var instance: PulsaDatabase? = null

        fun get(context: Context): PulsaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PulsaDatabase::class.java,
                NAME
            )
                .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                .build()
                .also { instance = it }
        }
    }
}
