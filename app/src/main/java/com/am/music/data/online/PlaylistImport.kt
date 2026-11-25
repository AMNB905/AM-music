package com.am.music.data.online

import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song

/**
 * 歌单导入的两块纯逻辑：从分享文案里认链接，以及一次取到的歌单内容。
 *
 * 为什么单独一个文件：解析是纯字符串处理，能被单元测试、也能被别的入口（比如以后的
 * 「从剪贴板直接导入」）复用，不必牵出网络那一层。
 */

/** 歌单本身的元信息（不含曲目）。两个平台的字段名不同，解析层已归一到这里。 */
data class PlaylistMeta(
    val name: String?,
    val creator: String?,
    val coverUrl: String?,
    val trackCount: Int,
)

/** 一次导入拿到的歌单内容。名字可能是 null（上游偶发不给），界面用兜底名。 */
data class PlaylistBundle(
    val name: String?,
    val creator: String?,
    val coverUrl: String?,
    val trackCount: Int,
    val songs: List<Song>,
)

sealed interface PlaylistLinkResult {
    data class Ok(val platform: OnlinePlatform, val id: String) : PlaylistLinkResult

    /**
     * 认出了平台、但链接里没有数字歌单 id —— QQ 音乐分享卡片的二维码就是这种
     * （`https://c6.y.qq.com/base/fcgi-bin/u?__=…`，302 之后才是带 `id=` 的地址）。
     * 交给 [LinkResolver] 展开一次再解析。
     */
    data class ShortLink(val platform: OnlinePlatform, val url: String) : PlaylistLinkResult

    /** [message] 是给用户看的话，直接显示，不再二次解释。 */
    data class Failed(val message: String) : PlaylistLinkResult
}

object PlaylistLinkParser {

    private val URL_RE = Regex("""https?://[^\s<>"'“”，。；、）)】\]]+""")

    /**
     * 从一整段分享文案里认歌单。
     *
     * 分享文案长这样（真机复制的原样）：
     * `7.92 复制打开抖音…` 这种是视频；歌单的是
     * `AM-大帝喜欢的音乐 AM-大帝 https://music.163.com/m/playlist?id=7415309581&creatorId=7840248460`。
     * 所以**不能把整段当 URL**，只挑出第一条 http(s) 链接，再从它的 query / path 里取 id。
     */
    fun parse(text: String): PlaylistLinkResult {
        val raw = text.trim()
        if (raw.isEmpty()) return PlaylistLinkResult.Failed("先粘贴歌单链接。")

        val url = URL_RE.find(raw)?.value?.trimEnd('.', ',', '，', '。', '；', ';', '）', ')', '】')
            ?: return PlaylistLinkResult.Failed("没找到链接，整段分享文案直接粘也行")

        val uri = runCatching { java.net.URI(url) }.getOrNull()
            ?: return PlaylistLinkResult.Failed("这段链接读不出来，换一条再试。")

        val host = uri.host?.lowercase().orEmpty()
        val platform = when {
            host.contains("163cn.tv") || host.contains("music.163.com") || host.contains("163.com") ->
                OnlinePlatform.NETEASE

            host.contains("qq.com") -> OnlinePlatform.QQ
            else -> return PlaylistLinkResult.Failed("只支持网易云 / QQ 歌单链接")
        }

        // query 可能在 `?` 后面，也可能在 `#/playlist?id=` 这种 hash 路由里
        val query = buildString {
            append(uri.rawQuery.orEmpty())
            val fragment = uri.rawFragment.orEmpty()
            if (fragment.contains('?')) {
                if (isNotEmpty()) append('&')
                append(fragment.substringAfter('?'))
            }
        }
        val params = query.split('&').mapNotNull { pair ->
            val key = pair.substringBefore('=').trim()
            if (key.isEmpty()) return@mapNotNull null
            key.lowercase() to pair.substringAfter('=', "").trim()
        }.toMap()

        val id = when (platform) {
            OnlinePlatform.NETEASE -> params["id"]
            OnlinePlatform.QQ -> params["id"] ?: params["disstid"]
        } ?: numericSegment(uri.path)

        if (id.isNullOrBlank()) {
            // 没 id 不等于没救：分享卡片的二维码／短链展开后就是带 id 的地址，先让上层展开一次
            return PlaylistLinkResult.ShortLink(platform, url)
        }
        if (!id.all { it.isDigit() }) {
            return PlaylistLinkResult.Failed("歌单 id 不对（$id），换个链接")
        }
        return PlaylistLinkResult.Ok(platform, id)
    }

    /** `.../playlist/8319686015` 这类没有 query 的路径，取最后一个够长的数字段。 */
    private fun numericSegment(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return path.split('/').lastOrNull { segment ->
            segment.length >= 5 && segment.all { it.isDigit() }
        }
    }
}
