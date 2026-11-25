package com.am.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 收藏 / 歌单里保存的不再只是「一个 id」，而是曲目的完整信息。
 *
 * 0.4.0 之前只存 songId，恢复时靠 MediaStore 反查 —— 所以在线曲目存进去也还原不出来，
 * 只能把在线曲目的收藏/加歌单入口藏起来。现在把这些字段落库：
 * - `platform` 为空 = 本地曲目（用 `uri` 或 MediaStore 反查）
 * - `platform` 非空 = 在线曲目（`qq` / `netease`，播放时再解析直链）
 */

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: String,
    val addedAt: Long,
    val platform: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val uri: String? = null,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long,
    val platform: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val uri: String? = null,
)

data class PlaylistWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
)

/** 最近播放：只保留最近 [RECENT_LIMIT] 首，同一首再播就刷新时间并置顶。 */
@Entity(tableName = "recent_songs")
data class RecentSongEntity(
    @PrimaryKey val songId: String,
    val playedAt: Long,
    val platform: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @ColumnInfo(defaultValue = "0") val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val uri: String? = null,
)

const val RECENT_LIMIT = 50
