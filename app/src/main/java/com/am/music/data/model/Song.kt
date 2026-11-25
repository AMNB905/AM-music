package com.am.music.data.model

import android.net.Uri

enum class SongSource { LOCAL, ONLINE }

/** 在线音源平台；key 与后端路由前缀一致（/Music/qq/、/Music/wy/）。 */
enum class OnlinePlatform(val key: String, val label: String) {
    QQ("qq", "QQ音乐"),
    NETEASE("netease", "网易云");

    companion object {
        fun fromKey(key: String?): OnlinePlatform? = entries.firstOrNull { it.key == key }
    }
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri? = null,
    val source: SongSource = SongSource.LOCAL,
    val localPath: String? = null,
    val platform: OnlinePlatform? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    /**
     * 本地缓存里已经有这首歌**完整**音频时，记下缓存对应的音质档位（mini/standard/hq/lossless…）。
     * 非空表示「可以脱网播」：此时 [uri] 只是个占位地址，真正读的是磁盘缓存。
     */
    val cachedQuality: String? = null,
    /**
     * 平台侧的**另一个 id**：QQ 的数字 songid（`extra.song_id`）。
     *
     * `id` 是 songmid（直链、歌词都按它走），但「相似歌曲」那个接口要的是数字 id，
     * 两套标识缺一不可。只在顺手能从接口读到的地方带上（榜单/每日推荐/歌单），
     * 落库不存它——查不到就不显示入口，而不是留个点了没反应的按钮。
     */
    /**
     * 已**下载**（不是播放缓存）的档位。非空时 [uri] 指向 filesDir 里的真实文件，
     * 不依赖直链、不依赖 LRU，离线一定放得出来。
     */
    val downloadedQuality: String? = null,
    /**
     * 平台侧的**另一个 id**：QQ 的数字 songid（`extra.song_id`）。
     *
     * `id` 是 songmid（直链、歌词都按它走），但「相似歌曲」那个接口要的是数字 id，
     * 两套标识缺一不可。只在顺手能从接口读到的地方带上（榜单/每日推荐/歌单），
     * 落库不存它——查不到就不显示入口，而不是留个点了没反应的按钮。
     */
    val sourceId: String? = null,
) {
    /**
     * 跨来源唯一键。本地保持原 MediaStore id（兼容既有收藏/歌单数据），
     * 在线为「平台:id」，避免网易数字 id 与本地 id 撞车。
     */
    val key: String
        get() = when {
            source == SongSource.LOCAL || platform == null -> id
            else -> "${platform.key}:$id"
        }

    val isOnline: Boolean
        get() = source == SongSource.ONLINE || platform != null
}
