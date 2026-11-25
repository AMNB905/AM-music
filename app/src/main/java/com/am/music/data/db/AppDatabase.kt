package com.am.music.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        RecentSongEntity::class,
        DownloadEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun recentSongsDao(): RecentSongsDao
    abstract fun downloadsDao(): DownloadsDao

    companion object {

        /**
         * 1 → 2：给收藏表和歌单曲目表补上曲目信息列。
         * 旧行只有 songId，新列一律 NULL/0，读取时仍会走「本地曲库反查」这条老路径，不会丢数据。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("favorites", "playlist_songs").forEach { table ->
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `platform` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `title` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `artist` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `album` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `coverUrl` TEXT")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `uri` TEXT")
                }
            }
        }

        /** 2 → 3：新增「最近播放」表（与收藏/歌单同一套曲目信息列）。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_songs` (" +
                        "`songId` TEXT NOT NULL, " +
                        "`playedAt` INTEGER NOT NULL, " +
                        "`platform` TEXT, " +
                        "`title` TEXT, " +
                        "`artist` TEXT, " +
                        "`album` TEXT, " +
                        "`durationMs` INTEGER NOT NULL DEFAULT 0, " +
                        "`coverUrl` TEXT, " +
                        "`uri` TEXT, " +
                        "PRIMARY KEY(`songId`))"
                )
            }
        }

        /** 3 → 4：新增「下载」表（一档音质一行，主键 (songKey, quality)，多档可共存）。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `downloads` (" +
                        "`songKey` TEXT NOT NULL, " +
                        "`quality` TEXT NOT NULL, " +
                        "`songId` TEXT NOT NULL, " +
                        "`platform` TEXT, " +
                        "`sourceId` TEXT, " +
                        "`title` TEXT NOT NULL, " +
                        "`artist` TEXT NOT NULL, " +
                        "`album` TEXT NOT NULL, " +
                        "`durationMs` INTEGER NOT NULL DEFAULT 0, " +
                        "`coverUrl` TEXT, " +
                        "`fileName` TEXT NOT NULL, " +
                        "`bytes` INTEGER NOT NULL, " +
                        "`kbps` INTEGER NOT NULL DEFAULT 0, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`songKey`, `quality`))"
                )
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "am_music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
