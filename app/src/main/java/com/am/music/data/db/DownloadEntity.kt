package com.am.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 一首歌的一个音质档位的下载记录。
 *
 * 主键是 (songKey, quality)：同一首歌的 无损 / 320K / 128K 是三条独立记录、三个独立文件，
 * 想共存就共存，删一个不影响其它档位。
 *
 * 用 filesDir 而不是 cacheDir —— 这是用户主动要的「下载」，不能被 LRU 清掉；
 * App 卸载才跟着消失。
 */
@Entity(
    tableName = "downloads",
    primaryKeys = ["songKey", "quality"],
)
data class DownloadEntity(
    val songKey: String,
    val quality: String,
    val songId: String,
    val platform: String? = null,
    val sourceId: String? = null,
    val title: String,
    val artist: String,
    val album: String = "",
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val fileName: String,
    val bytes: Long,
    @ColumnInfo(defaultValue = "0") val kbps: Int = 0,
    val createdAt: Long,
)

@Dao
interface DownloadsDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE songKey = :songKey ORDER BY createdAt DESC")
    suspend fun bySong(songKey: String): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE songKey = :songKey AND quality = :quality LIMIT 1")
    suspend fun find(songKey: String, quality: String): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songKey = :songKey AND quality = :quality")
    suspend fun deleteOne(songKey: String, quality: String)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}
