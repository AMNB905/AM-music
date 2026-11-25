package com.am.music.data.online

import com.am.music.data.model.OnlinePlatform

/** 后端推荐歌单（QQ：qq/api/playlist/recommend；网易：sy/netease_playlists.php）。 */
data class OnlinePlaylist(
    val id: String,
    val name: String,
    val coverUrl: String?,
    val playCount: Long,
    val trackCount: Int,
    val creator: String,
    val platform: OnlinePlatform,
) {
    val key: String
        get() = "${platform.key}:$id"
}
