package com.am.music.data.online

import com.am.music.data.model.Song

/**
 * 「在线」首页一屏所需的全部数据。
 *
 * 以前是 ViewModel 里并发拉四个接口、各自落到四个字段，切音源时要重来一遍；
 * 收成一个对象后，同一音源的数据可以整体记忆 / 整体落盘，切回去直接复用。
 */
data class DiscoverFeed(
    val hotKeywords: List<String> = emptyList(),
    val playlists: List<OnlinePlaylist> = emptyList(),
    val dailySongs: List<Song> = emptyList(),
    /** 音源连通性：null 表示还没测出来。 */
    val reachable: Boolean? = null,
) {
    /** 这一份里到底有没有内容（全空说明既没快照也没拉到）。 */
    val isEmpty: Boolean
        get() = hotKeywords.isEmpty() && playlists.isEmpty() && dailySongs.isEmpty()
}
