package com.am.music.data.cache

import coil.ImageLoader
import com.am.music.data.cover.CoverCache
import com.am.music.data.lyrics.LyricsRepository
import com.am.music.playback.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一处缓存当前的占用量。 */
data class CacheSnapshot(
    val audioBytes: Long,
    val imageBytes: Long,
    val lyricsBytes: Long,
) {
    val totalBytes: Long get() = audioBytes + imageBytes + lyricsBytes
}

/**
 * 缓存总账：把三处本地缓存（音频 / 封面 / 歌词）的占用统计与清理收在一处，
 * 设置页只跟它打交道。清理都是删文件，属于同步 IO，统一放到 IO 线程做。
 */
class AppCacheRepository(
    private val lyricsRepository: LyricsRepository,
    private val imageLoader: ImageLoader,
    /** 首页型数据（榜单 / 每日推荐 / 推荐歌单）快照，清缓存时一并清掉。 */
    private val feedCache: FeedCache? = null,
    /** 封面落盘（filesDir/covers），和 Coil 自己的 image_cache 是两套，要分别统计与清理。 */
    private val coverCache: CoverCache? = null,
) {

    suspend fun snapshot(): CacheSnapshot = withContext(Dispatchers.IO) {
        CacheSnapshot(
            audioBytes = MediaCache.size(),
            // 两处封面都算进来：Coil 的临时缓存 + 我们自己落盘的封面
            imageBytes = (imageLoader.diskCache?.size ?: 0L) + (coverCache?.bytes() ?: 0L),
            lyricsBytes = lyricsRepository.cacheBytes(),
        )
    }

    suspend fun clearAudio() = withContext(Dispatchers.IO) { MediaCache.clear() }

    suspend fun clearImages() = withContext(Dispatchers.IO) {
        imageLoader.memoryCache?.clear()
        imageLoader.diskCache?.clear()
        runCatching { coverCache?.clear() }
    }

    suspend fun clearLyrics() = withContext(Dispatchers.IO) { lyricsRepository.clearCache() }

    suspend fun clearAll() {
        clearAudio()
        clearImages()
        clearLyrics()
        withContext(Dispatchers.IO) { feedCache?.clear() }
    }
}
