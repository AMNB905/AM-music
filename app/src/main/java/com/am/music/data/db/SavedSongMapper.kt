package com.am.music.data.db

import android.net.Uri
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongSource

/**
 * 收藏 / 歌单落库后的还原规则：
 *
 * 1. `platform` 非空 → 在线曲目，直接按存的元数据还原（直链播放时再解析）；
 * 2. `platform` 为空 → 本地曲目：优先用 MediaStore 里现查到的这条（uri 更新、文件可能被移动过），
 *    查不到再退回库里存的 `uri`（外部存储重新挂载后也能播）；
 * 3. 两条都不成立（旧版只存 id 且文件已不存在）→ 返回 null，调用方过滤掉。
 */
internal fun buildSavedSong(
    songId: String,
    platform: String?,
    title: String?,
    artist: String?,
    album: String?,
    durationMs: Long,
    coverUrl: String?,
    uri: String?,
    localLibrary: Map<String, Song>,
): Song? {
    val onlinePlatform = OnlinePlatform.fromKey(platform)
    if (onlinePlatform != null) {
        val cover = coverUrl?.let { runCatching { Uri.parse(it) }.getOrNull() }
        return Song(
            id = songId.substringAfter(':'),
            title = title?.takeIf { it.isNotBlank() } ?: "未命名歌曲",
            artist = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家",
            album = album.orEmpty(),
            durationMs = durationMs,
            uri = Uri.EMPTY,
            albumArtUri = cover,
            source = SongSource.ONLINE,
            platform = onlinePlatform,
            coverUrl = coverUrl,
        )
    }

    localLibrary[songId]?.let { return it }

    val storedUri = uri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return null
    if (storedUri == Uri.EMPTY) return null
    return Song(
        id = songId,
        title = title?.takeIf { it.isNotBlank() } ?: "未命名歌曲",
        artist = artist?.takeIf { it.isNotBlank() } ?: "未知艺术家",
        album = album.orEmpty(),
        durationMs = durationMs,
        uri = storedUri,
        albumArtUri = coverUrl?.let { runCatching { Uri.parse(it) }.getOrNull() },
        source = SongSource.LOCAL,
        coverUrl = coverUrl,
    )
}

internal fun FavoriteEntity.toSong(localLibrary: Map<String, Song>): Song? =
    buildSavedSong(
        songId = songId,
        platform = platform,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        uri = uri,
        localLibrary = localLibrary,
    )

internal fun PlaylistSongEntity.toSong(localLibrary: Map<String, Song>): Song? =
    buildSavedSong(
        songId = songId,
        platform = platform,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        uri = uri,
        localLibrary = localLibrary,
    )

internal fun RecentSongEntity.toSong(localLibrary: Map<String, Song>): Song? =
    buildSavedSong(
        songId = songId,
        platform = platform,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        uri = uri,
        localLibrary = localLibrary,
    )

internal fun Song.toRecentSongEntity(): RecentSongEntity = RecentSongEntity(
    songId = key,
    playedAt = System.currentTimeMillis(),
    platform = platform?.key,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    coverUrl = coverUrl,
    uri = uri.toString().takeIf { uri != Uri.EMPTY },
)

/** 落库时统一用 `Song.key` 作为主键：本地 = 媒体 id，在线 = `平台:id`，两套体系不会撞。 */
internal fun Song.toFavoriteEntity(): FavoriteEntity = FavoriteEntity(
    songId = key,
    addedAt = System.currentTimeMillis(),
    platform = platform?.key,
    title = title,
    artist = artist,
    album = album,
    durationMs = durationMs,
    coverUrl = coverUrl,
    uri = uri.toString().takeIf { uri != Uri.EMPTY },
)

internal fun Song.toPlaylistSongEntity(playlistId: Long, position: Int): PlaylistSongEntity =
    PlaylistSongEntity(
        playlistId = playlistId,
        songId = key,
        position = position,
        addedAt = System.currentTimeMillis(),
        platform = platform?.key,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        coverUrl = coverUrl,
        uri = uri.toString().takeIf { uri != Uri.EMPTY },
    )
