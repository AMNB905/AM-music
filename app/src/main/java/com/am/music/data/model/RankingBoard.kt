package com.am.music.data.model

/**
 * 榜单条目。
 *
 * id 是各平台**固定不变**的榜单标识（网易云是歌单 id，QQ 是 topid），本地写死即可，
 * 不用再从服务端拉目录；note 只当界面副标题。
 */
data class RankingBoard(
    val id: String,
    val name: String,
    val note: String,
    val platform: OnlinePlatform = OnlinePlatform.NETEASE,
) {
    /** 缓存 / 去重用的唯一键：两个平台的数字 id 会撞，所以加平台前缀。 */
    val key: String get() = "${platform.key}:$id"
}

object RankingBoards {

    /** 网易云四大榜（playlist/detail 的榜单歌单 id，长期固定）。 */
    val NETEASE: List<RankingBoard> = listOf(
        RankingBoard(id = "19723756", name = "飙升榜", note = "此刻增长最快", platform = OnlinePlatform.NETEASE),
        RankingBoard(id = "3778678", name = "热歌榜", note = "全网最热", platform = OnlinePlatform.NETEASE),
        RankingBoard(id = "3779629", name = "新歌榜", note = "最新发布", platform = OnlinePlatform.NETEASE),
        RankingBoard(id = "2884035", name = "原创榜", note = "独立音乐", platform = OnlinePlatform.NETEASE),
    )

    /** QQ 音乐四大榜（topid 取自 y.qq.com/n/ryqq/toplist/<id> 的官方标题）。 */
    val QQ: List<RankingBoard> = listOf(
        RankingBoard(id = "62", name = "飙升榜", note = "此刻涨得最快", platform = OnlinePlatform.QQ),
        RankingBoard(id = "26", name = "热歌榜", note = "全网最热", platform = OnlinePlatform.QQ),
        RankingBoard(id = "27", name = "新歌榜", note = "最新发布", platform = OnlinePlatform.QQ),
        RankingBoard(id = "4", name = "流行指数榜", note = "流行度加权", platform = OnlinePlatform.QQ),
    )

    val ALL: List<RankingBoard> = NETEASE + QQ

    fun of(platform: OnlinePlatform): List<RankingBoard> =
        if (platform == OnlinePlatform.QQ) QQ else NETEASE
}
