package com.am.music.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.am.music.data.model.Song

/**
 * 曲目 → MediaItem。
 *
 * `cacheKey` 必须带上音质：直链是「一次一签」的地址，URL 本身每次都不同，
 * 不带音质区分会让不同码流挤在同一个缓存 key 上。
 *
 * 带 [Song.cachedQuality] 的曲目表示「整首已落在本地」：这时用占位 uri + 与缓存
 * 档位一致的 cacheKey，播放器直接读磁盘，既不解析直链也不回源（离线也能放）。
 */
@OptIn(UnstableApi::class)
internal fun Song.toMediaItem(quality: String?): MediaItem {
    // 已下载：uri 就是本地文件，既不读缓存占位地址，也别再往播放缓存里抄一份
    if (downloadedQuality != null && localPath != null) {
        return MediaItem.Builder()
            .setMediaId(key)
            .setUri(Uri.fromFile(java.io.File(localPath)))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(albumArtUri)
                    .build()
            )
            .build()
    }
    val effectiveQuality = cachedQuality ?: quality
    val itemUri = if (cachedQuality != null) {
        MediaCache.placeholderUri(key, cachedQuality)
    } else {
        uri
    }
    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(itemUri)
        .setCustomCacheKey(
            if (effectiveQuality.isNullOrEmpty()) key else MediaCache.cacheKey(key, effectiveQuality)
        )
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(albumArtUri)
                .build()
        )
        .build()
}
