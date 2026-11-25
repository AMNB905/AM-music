package com.am.music.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * 音频磁盘缓存（进程级单例）。
 *
 * 只做两件事：
 * 1. 同一个 `cacheKey` 的音频流第二次播放、或拖动进度条回到听过的地方，直接读本地，不再回源；
 * 2. 告诉上层「这首歌是不是已经整首在本地了」，让离线时也能起播、在线时优先挖缓存。
 *
 * cacheKey 统一为 `songKey@quality`：直链是一次一签的地址，光看 URL 认不出同一首歌，
 * 带上音质又能避免不同码流挤在同一个 key 上。
 *
 * 必须是单例：`SimpleCache` 对同一个目录只能有一个实例，否则第二个会抛
 * `IllegalStateException: Another SimpleCache instance uses the folder`。
 */
@OptIn(UnstableApi::class)
object MediaCache {

    private const val MAX_BYTES = 1024L * 1024 * 1024 // 1 GB，按 LRU 淘汰

    /** 离线播放时用的占位地址：真正读的是缓存，不会真的去访问这个域。 */
    const val PLACEHOLDER_HOST = "offline.amcache.local"

    @Volatile
    private var instance: SimpleCache? = null

    fun get(context: Context): SimpleCache {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: SimpleCache(
                // **放在 filesDir，不放 cacheDir**：cacheDir 属于「系统可以随时清」的区域，
                // 低空间时整块被收走，用户看到的却是「已缓存」的歌——点了播不出来，还没有任何提示。
                // 放 filesDir 之后，只有设置页的清理按钮会动它，离线能不能播才是确定的。
                File(context.applicationContext.filesDir, "audio_cache"),
                LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { instance = it }
        }
    }

    /** 缓存 key 的唯一拼法，播放链路与判定链路必须一致。 */
    fun cacheKey(songKey: String, quality: String): String = "$songKey@$quality"

    /** 离线播放用的占位 Uri（cacheKey 里带 @ 与冒号，编码一次避免被当成路径分隔）。 */
    fun placeholderUri(songKey: String, quality: String): Uri =
        Uri.parse("https://$PLACEHOLDER_HOST/${Uri.encode(cacheKey(songKey, quality))}")

    /** 当前占用（按 span 累加已缓存字节）。未初始化时视为 0，不为了统计去建缓存目录。 */
    fun size(): Long {
        val cache: Cache = instance ?: return 0L
        return runCatching {
            cache.keys.fold(0L) { total, key -> total + cache.getCachedBytes(key, 0L, Long.MAX_VALUE) }
        }.getOrDefault(0L)
    }

    /**
     * 是否已经整首落在本地（只有整首的才算「可脱网播」）。
     *
     * 这里曾经有过一条「总长度拿不到时，看第一个片段封口没封口」的兜底，是**错的**：
     * 下载被打断（切歌、退后台、断网）时 CacheWriter 一样会把片段封口，
     * 于是「下了三分之一」被判成「整首都在」，离线起播读到这里就再也读不动 —— 这正是
     * 「播到一半卡住、还没有任何提示」的源头。现在只认一种证据：已知总长度，且 0..total 全在。
     * 总长度拿不到的，一律当「没缓存完」，回源继续流式播（顺带把缺的部分补上）。
     */
    fun isFullyCached(key: String): Boolean {
        val cache: Cache = instance ?: return false
        return runCatching {
            val total = cache.getContentMetadata(key)
                .get(ContentMetadata.KEY_CONTENT_LENGTH, -1L)
            total > 0 && cache.isCached(key, 0L, total)
        }.getOrDefault(false)
    }

    /** 本地已有多少字节（给「缓存不完整」这类提示提供依据，不参与判定）。 */
    fun cachedBytes(key: String): Long {
        val cache: Cache = instance ?: return 0L
        return runCatching { cache.getCachedBytes(key, 0L, Long.MAX_VALUE) }.getOrDefault(0L)
    }

    /**
     * 这首歌在本地已有的完整缓存档位。
     *
     * 先看当前设置的音质，命中就立刻返回；没命中再扫这首歌名下的其它档位——
     * 用户切过音质时，上一档的整首缓存同样能脱网播。
     * 传 context 是为了在「冷启动 + 离线、还没播过任何歌」时也能把缓存索引建起来。
     */
    fun cachedQuality(songKey: String, preferred: String?, context: Context? = null): String? {
        val cache: Cache = instance ?: context?.let { get(it) } ?: return null
        val preferredKey = preferred?.takeIf { it.isNotBlank() }?.let { cacheKey(songKey, it) }
        if (preferredKey != null && isFullyCached(preferredKey)) return preferred
        return runCatching {
            cache.keys.asSequence()
                .filter { it.startsWith("$songKey@") }
                .mapNotNull { key -> key.substringAfterLast('@').takeIf { isFullyCached(key) } }
                .firstOrNull()
        }.getOrNull()
    }

    /** 清空缓存（设置页「清理音频缓存」）。逐 key removeResource 是 SimpleCache 唯一安全的删法。 */
    fun clear() {
        val cache: Cache = instance ?: return
        runCatching {
            cache.keys.toList().forEach { key ->
                cache.removeResource(key)
            }
        }
    }
}
