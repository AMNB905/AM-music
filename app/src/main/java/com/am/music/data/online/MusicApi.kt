package com.am.music.data.online

import com.am.music.data.cache.FeedCache
import com.am.music.data.cover.CoverUrls
import com.am.music.data.model.ClipParse
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.Song
import com.am.music.data.model.SongFacts
import com.am.music.data.model.VideoClip
import com.am.music.data.model.SongSource
import com.am.music.data.security.SecuritySession
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 音乐后端 HTTP 客户端。
 *
 * 根地址：https://xiaorou.top/Music/
 * 接口路径与后端压缩包内保持一致（逐条对照 server.php / index.php / PHP-API-INDEX.md）：
 *
 *  QQ 音源   qq/api/search          ?q=&n=
 *            qq/api/url             ?mid=&quality=
 *            qq/api/lyric           ?mid=
 *            qq/api/hotkey          ?num=
 *            qq/api/playlist/recommend
 *            qq/api/playlist/detail ?id=
 *            qq/api/health
 *
 *  网易音源  wy/api/search          ?q=
 *            wy/api/url             ?id=&quality=
 *            wy/api/lyric           ?id=
 *            wy/hot.php                         （code=200 体系）
 *            wy/api/health
 *            sy/netease_playlists.php?limit=    （code=200 体系，首页推荐歌单）
 *            sy/netease_playlist_detail.php?id=（code=200 体系，歌单全量歌曲）
 */
class MusicApi(
    private val baseUrl: String = BASE_URL,
    private val security: SecuritySession? = null,
    /** 首页型数据（榜单 / 每日推荐 / 推荐歌单）的磁盘快照；null 表示不缓存。 */
    private val feedCache: FeedCache? = null,
) {

    /**
     * 同一条请求的单飞表：同一个 `path?query` 只允许一条在飞，后来的等它的结果。
     *
     * 这一层是「并发承受能力」的地基——首页、歌单抽屉、预取、封面预热会在同一刻
     * 各自要同一份数据，没有它就会同一条接口被并发打三四次，既慢又容易被上游限流。
     */
    private val inflight = ConcurrentHashMap<String, Deferred<String?>>()

    /** 单飞协程的作用域：不跟随调用方的生命周期，调用方取消也不会把公共请求打断。 */
    private val apiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 首页型数据的统一取数口径：**每个进程启动只真正拉一次**。
     *
     * - 本次进程已成功拉过同一 key → 直接读盘，不发请求（切榜、来回进出都不再打接口）；
     * - 首次访问（含冷启动）→ 发请求，成功后落盘；失败就退回旧快照，至少有内容可看；
     * - [forceRefresh] 只在用户点「刷新」时置 true。
     */
    private suspend fun cachedRequest(
        cacheKey: String,
        forceRefresh: Boolean,
        path: String,
        params: Map<String, String>,
    ): String? {
        val cache = feedCache ?: return request(path, params)
        if (!forceRefresh && cache.isFresh(cacheKey)) {
            cache.read(cacheKey)?.let { return it }
        }
        val body = request(path, params)
        if (body != null && body.isNotBlank() && isHealthyBody(body)) {
            cache.write(cacheKey, body)
            cache.markFresh(cacheKey)
            return body
        }
        return cache.read(cacheKey) ?: body
    }

    /**
     * 只缓存「真拿到数据」的响应。
     *
     * 上游失败也可能以 HTTP 200 + `{"code":502,...}` 的形式回来（`sy/qq_toplist.php` 就是这样兜底的），
     * 这种空壳不能写进快照、更不能打 fresh 标记，否则整次启动都要对着一个空榜单。
     */
    private fun isHealthyBody(body: String): Boolean = runCatching {
        val json = JSONObject(body)
        val code = if (json.has("code")) json.optInt("code", -1) else 0
        code == 0 || code == 200
    }.getOrDefault(false)

    suspend fun search(keyword: String, platform: OnlinePlatform): List<Song> =
        withContext(Dispatchers.IO) {
            val body = when (platform) {
                OnlinePlatform.QQ -> request(p("p01"), mapOf("q" to keyword, "n" to "30"))
                OnlinePlatform.NETEASE -> request(p("p02"), mapOf("q" to keyword))
            } ?: return@withContext emptyList()
            runCatching {
                val json = JSONObject(body)
                val array = json.optJSONArray("data") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    parseSong(array.optJSONObject(index), platform)
                }
            }.getOrDefault(emptyList())
        }

    suspend fun streamUrl(song: Song, quality: String = "auto"): String? =
        withContext(Dispatchers.IO) {
            val platform = song.platform ?: return@withContext null
            val body = when (platform) {
                OnlinePlatform.QQ -> request(
                    p("p03"),
                    mapOf("mid" to song.id, "quality" to quality),
                )

                OnlinePlatform.NETEASE -> request(
                    p("p04"),
                    mapOf("id" to song.id, "quality" to quality),
                )
            } ?: return@withContext null
            runCatching {
                val data = JSONObject(body).optJSONObject("data")
                httpUrl(data?.optString("url").orEmpty())
            }.getOrNull()
        }

    suspend fun lyrics(song: Song): String? = withContext(Dispatchers.IO) {
        val platform = song.platform ?: return@withContext null
        val body = when (platform) {
            OnlinePlatform.QQ -> request(p("p05"), mapOf("mid" to song.id))
            OnlinePlatform.NETEASE -> request(p("p06"), mapOf("id" to song.id))
        } ?: return@withContext null
        runCatching {
            normalizeLrc(JSONObject(body).optString("data"))
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    suspend fun hotKeywords(platform: OnlinePlatform, forceRefresh: Boolean = false): List<String> =
        withContext(Dispatchers.IO) {
            when (platform) {
                OnlinePlatform.QQ -> {
                    val body = cachedRequest(
                        cacheKey = hotKey(platform),
                        forceRefresh = forceRefresh,
                        path = p("p07"),
                        params = mapOf("num" to "12"),
                    ) ?: return@withContext emptyList()
                    parseHotQq(body)
                }

                OnlinePlatform.NETEASE -> {
                    val body = cachedRequest(
                        cacheKey = hotKey(platform),
                        forceRefresh = forceRefresh,
                        path = p("p08"),
                        params = emptyMap(),
                    ) ?: return@withContext emptyList()
                    parseHotNetease(body)
                }
            }
        }

    /** 热词快照：冷启动那一排「热门搜索」不必再等一次接口。 */
    suspend fun hotKeywordsSnapshot(platform: OnlinePlatform): List<String>? =
        withContext(Dispatchers.IO) {
            val body = snapshotBody(hotKey(platform)) ?: return@withContext null
            when (platform) {
                OnlinePlatform.QQ -> parseHotQq(body)
                OnlinePlatform.NETEASE -> parseHotNetease(body)
            }.takeIf { it.isNotEmpty() }
        }

    private fun hotKey(platform: OnlinePlatform): String = "hot-${platform.key}"

    private fun parseHotQq(body: String): List<String> = runCatching {
        val array = JSONObject(body).optJSONArray("data") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            item.optString("query").ifBlank { item.optString("title") }.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList()).distinct()

    private fun parseHotNetease(body: String): List<String> = runCatching {
        val array = JSONObject(body).optJSONArray("hots") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.optString("word")?.takeIf { it.isNotBlank() }
        }
    }.getOrDefault(emptyList()).distinct()

    /**
     * 首页「推荐歌单」。
     *
     * **两个平台共用同一份网易云歌单**：QQ 的 `qq/api/playlist/recommend` 给的是它自己的
     * 歌单，点开后要么歌名对不上、要么直链拿不到，实际可用度低；网易那份
     * (`sy/netease_playlists.php`) 有封面、曲目数、可完整取歌，所以统一用它，
     * 与顶部平台页签（QQ / 网易云）无关。参数 `platform` 只为签名兼容保留。
     */
    suspend fun playlists(
        platform: OnlinePlatform,
        limit: Int = 12,
        forceRefresh: Boolean = false,
    ): List<OnlinePlaylist> =
        withContext(Dispatchers.IO) {
            val body = cachedRequest(
                cacheKey = playlistsKey(limit),
                forceRefresh = forceRefresh,
                path = p("p09"),
                params = mapOf("limit" to limit.toString()),
            ) ?: return@withContext emptyList()
            parsePlaylists(body)
        }

    /** 只读磁盘快照（不发网络）：冷启动先把它画出来，页面不必对着骨架等接口。 */
    suspend fun playlistsSnapshot(limit: Int = 12): List<OnlinePlaylist>? =
        withContext(Dispatchers.IO) {
            snapshotBody(playlistsKey(limit))?.let(::parsePlaylists)?.takeIf { it.isNotEmpty() }
        }

    private fun playlistsKey(limit: Int): String = "playlists-netease-$limit"

    private fun parsePlaylists(body: String): List<OnlinePlaylist> = runCatching {
        val json = JSONObject(body)
        val array = json.optJSONArray("data") ?: json.optJSONArray("items") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            parsePlaylist(array.optJSONObject(index), OnlinePlatform.NETEASE)
        }
    }.getOrDefault(emptyList())

    /** 快照一律先过一遍「是不是空壳 JSON」，坏快照不参与渲染。 */
    private fun snapshotBody(cacheKey: String): String? =
        feedCache?.read(cacheKey)?.takeIf { isHealthyBody(it) }

    /**
     * 榜单：网易云走 `sy/netease_toplist.php`，QQ 走 `sy/qq_toplist.php`（topid）。
     *
     * 两个端点都返回 items（id/name/artist/album/duration|durationMs/cover），与 [parseSong] 对齐。
     * 结果按 `feedCache` 口径缓存：本次进程没拉过才发请求，[forceRefresh] 用于用户手动刷新。
     */
    suspend fun toplistSongs(
        boardId: String,
        limit: Int = 50,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
        forceRefresh: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        val path = if (platform == OnlinePlatform.QQ) p("p10") else p("p11")
        val body = cachedRequest(
            cacheKey = toplistKey(boardId, limit, platform),
            forceRefresh = forceRefresh,
            path = path,
            params = mapOf("id" to boardId, "limit" to limit.toString()),
        ) ?: return@withContext emptyList()
        parseToplist(body, platform)
    }

    /** 榜单的磁盘快照（冷启动直接出内容，不等网络）。 */
    suspend fun toplistSongsSnapshot(
        boardId: String,
        limit: Int = 50,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
    ): List<Song>? = withContext(Dispatchers.IO) {
        snapshotBody(toplistKey(boardId, limit, platform))
            ?.let { parseToplist(it, platform) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun toplistKey(boardId: String, limit: Int, platform: OnlinePlatform): String =
        "toplist-${platform.key}-$boardId-$limit"

    private fun parseToplist(body: String, platform: OnlinePlatform): List<Song> = runCatching {
        val array = JSONObject(body).optJSONArray("items") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            parseSong(array.optJSONObject(index), platform)
        }
        // 榜单行用 song.key 当 LazyColumn 的行 key，上游若返回同一首两次会直接崩
    }.getOrDefault(emptyList()).distinctBy { it.key }

    /**
     * 每日推荐：网易云 `sy/netease_daily_songs.php`，QQ `qq/api/daily`（每日30首官方歌单）。
     *
     * QQ 那份是 `{data:{songs:[{id,mid,name,singer,album,albummid,interval}]}}`，
     * 这里统一映射成 [Song]：**id 用 songmid**（直链接口按 mid 取），时长按秒换算。
     */
    suspend fun dailySongs(
        limit: Int = 30,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
        forceRefresh: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        when (platform) {
            OnlinePlatform.NETEASE -> {
                val body = cachedRequest(
                    cacheKey = dailyKey(limit, platform),
                    forceRefresh = forceRefresh,
                    path = p("p12"),
                    params = mapOf("limit" to limit.toString()),
                ) ?: return@withContext emptyList()
                parseNeteaseDaily(body)
            }

            OnlinePlatform.QQ -> {
                val body = cachedRequest(
                    cacheKey = dailyKey(limit, platform),
                    forceRefresh = forceRefresh,
                    path = p("p13"),
                    params = emptyMap(),
                ) ?: return@withContext emptyList()
                parseQqDaily(body).take(limit)
            }
        }
    }

    /** 每日推荐的磁盘快照：冷启动先出封面墙，再让后台刷新把新数据换上来。 */
    suspend fun dailySongsSnapshot(
        limit: Int = 30,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
    ): List<Song>? = withContext(Dispatchers.IO) {
        val body = snapshotBody(dailyKey(limit, platform)) ?: return@withContext null
        val songs = when (platform) {
            OnlinePlatform.NETEASE -> parseNeteaseDaily(body)
            OnlinePlatform.QQ -> parseQqDaily(body).take(limit)
        }
        songs.takeIf { it.isNotEmpty() }
    }

    private fun dailyKey(limit: Int, platform: OnlinePlatform): String =
        if (platform == OnlinePlatform.QQ) "daily-qq" else "daily-netease-$limit"

    private fun parseNeteaseDaily(body: String): List<Song> = runCatching {
        val array = JSONObject(body).optJSONArray("items") ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            parseSong(array.optJSONObject(index), OnlinePlatform.NETEASE)
        }
    }.getOrDefault(emptyList())

    /** QQ 每日推荐原始结构 → Song（songmid 当 id，封面由 albummid 拼）。 */
    /**
     * 相似歌曲（新接口 `apps/netease` / `apps/qqmusic`，都是匿名可用）。
     *
     * 网易云直接按歌曲 id 查；QQ 那个要的是**数字 songid**（不是 songmid），
     * 只有从榜单 / 每日推荐 / 歌单里顺手带上了 [Song.sourceId] 的曲目才查得到，
     * 查不到返回空列表，界面据此不显示入口。
     */
    suspend fun similarSongs(song: Song, forceRefresh: Boolean = false): List<Song> =
        withContext(Dispatchers.IO) {
            val platform = song.platform ?: return@withContext emptyList()
            val body = when (platform) {
                OnlinePlatform.NETEASE -> cachedRequest(
                    cacheKey = "similar-wy-${song.key}",
                    forceRefresh = forceRefresh,
                    path = p("p14"),
                    params = mapOf("act" to "similarsong", "id" to song.id),
                )

                OnlinePlatform.QQ -> {
                    val numeric = song.sourceId?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
                    cachedRequest(
                        cacheKey = "similar-qq-${song.key}",
                        forceRefresh = forceRefresh,
                        path = p("p15"),
                        params = mapOf("act" to "similarsong", "id" to numeric, "num" to "20"),
                    )
                }
            } ?: return@withContext emptyList()

            runCatching {
                val array = JSONObject(body).optJSONObject("data")?.optJSONArray("songs") ?: JSONArray()
                (0 until array.length()).mapNotNull { index ->
                    parseSimilarSong(array.optJSONObject(index), platform)
                }
            }.getOrDefault(emptyList())
        }

    /** 相似歌曲条目 → Song：两个平台字段名不同（artistNames/coverImgUrl vs singer/albummid）。 */

    /**
     * 歌曲资料（百科）。QQ 用 songmid，网易用数字 id，两边都能匿名取到。
     *
     * 只抽取「一眼能读完」的字段：曲风 / 语种 / 发行时间 / 公司 / 主要制作，加一段简介。
     * 返回值可能为空（老歌、冷门曲目上游没有百科），界面按「暂无资料」处理。
     */
    suspend fun songFacts(song: Song, forceRefresh: Boolean = false): SongFacts? =
        withContext(Dispatchers.IO) {
            val platform = song.platform ?: return@withContext null
            val body = when (platform) {
                OnlinePlatform.NETEASE -> cachedRequest(
                    cacheKey = "facts-wy-${song.key}",
                    forceRefresh = forceRefresh,
                    path = p("p14"),
                    params = mapOf("act" to "wiki", "id" to song.id),
                )

                OnlinePlatform.QQ -> cachedRequest(
                    cacheKey = "facts-qq-${song.key}",
                    forceRefresh = forceRefresh,
                    path = p("p15"),
                    params = mapOf("act" to "wiki", "songmid" to song.id),
                )
            } ?: return@withContext null

            runCatching {
                val data = JSONObject(body).optJSONObject("data") ?: return@withContext null
                when (platform) {
                    OnlinePlatform.QQ -> qqFacts(data, song)
                    OnlinePlatform.NETEASE -> neteaseFacts(data, song)
                }
            }.getOrNull()
        }



    /**
     * 歌单全部歌曲。
     *
     * 网易云必须走 `wy/api/playlist`：`sy/netease_playlist_detail.php` 的上游
     * weapi/v3/playlist/detail 匿名访问只给前 20 首（实测 481 首的歌单只回 20 条），
     * `wy/api/playlist` 会把 trackIds 里的歌全部取回来（实测 481/481）。
     *
     * 结果落 [FeedCache]：同一个歌单第二次打开直接读盘，不再等接口。
     */
    suspend fun playlistSongs(
        playlistId: String,
        platform: OnlinePlatform,
        forceRefresh: Boolean = false,
    ): List<Song> = withContext(Dispatchers.IO) {
        val path = if (platform == OnlinePlatform.QQ) p("p17") else p("p16")
        val body = cachedRequest(
            cacheKey = playlistKey(playlistId, platform),
            forceRefresh = forceRefresh,
            path = path,
            params = mapOf("id" to playlistId),
        ) ?: return@withContext emptyList()
        val songs = parsePlaylistSongs(body, platform)
        // 网易云的上游偶发只回 20 首，这时才走 sy 兜底（兜底结果也会落快照，下次不用重试）
        if (songs.isEmpty() && platform == OnlinePlatform.NETEASE) {
            songsFromSy(playlistId, platform)
        } else {
            songs
        }
    }

    /**
     * 歌单详情快照：再次打开同一个歌单不必等接口。
     *
     * 大歌单（实测 481 首）一次要几秒，用户「刚刚打开过」的期待是立刻能看见列表，
     * 这里读的是上一次落盘的那份，刷新留给用户点。
     */
    suspend fun playlistSongsSnapshot(playlistId: String, platform: OnlinePlatform): List<Song>? =
        withContext(Dispatchers.IO) {
            snapshotBody(playlistKey(playlistId, platform))
                ?.let { parsePlaylistSongs(it, platform) }
                ?.takeIf { it.isNotEmpty() }
        }

    private fun playlistKey(playlistId: String, platform: OnlinePlatform): String =
        "playlist-${platform.key}-$playlistId"

    /**
     * 导入歌单：一次拿到「名字 + 全部曲目」。
     *
     * 与 [playlistSongs] 的区别是**必须发请求**：用户点「导入」的那一刻要的是这个歌单
     * 现在的样子，而不是上次打开时的快照。拿到之后顺手写回快照，
     * 这样导入完从曲库点进同一个歌单也能直接读盘。
     *
     * 返回 null = 连服务器都没成功；曲目为空 = 连上了但没内容（私密/全下架/限流）。
     */
    suspend fun playlistBundle(
        playlistId: String,
        platform: OnlinePlatform,
    ): PlaylistBundle? = withContext(Dispatchers.IO) {
        val path = if (platform == OnlinePlatform.QQ) p("p17") else p("p16")
        // 大歌单（实测 742 首）服务端要逐首取，20 秒不够；这一条单独放宽到 90 秒
        val body = request(path, mapOf("id" to playlistId), PLAYLIST_TIMEOUT_MS)
            ?: return@withContext null

        val meta = MusicParsing.playlistMeta(body, platform)
        var songs = parsePlaylistSongs(body, platform)
        if (songs.isEmpty() && platform == OnlinePlatform.NETEASE) {
            songs = songsFromSy(playlistId, platform)
        }
        if (songs.isNotEmpty() && isHealthyBody(body)) {
            feedCache?.write(playlistKey(playlistId, platform), body)
            feedCache?.markFresh(playlistKey(playlistId, platform))
        }
        PlaylistBundle(
            name = meta?.name,
            creator = meta?.creator,
            coverUrl = meta?.coverUrl,
            trackCount = meta?.trackCount?.takeIf { it > 0 } ?: songs.size,
            songs = songs,
        )
    }

    private fun parsePlaylistSongs(body: String, platform: OnlinePlatform): List<Song> = runCatching {
        val array = JSONObject(body).optJSONObject("data")
            ?.optJSONArray(if (platform == OnlinePlatform.QQ) "songs" else "tracks")
            ?: JSONArray()
        (0 until array.length()).mapNotNull { index ->
            parseSong(array.optJSONObject(index), platform)
        }
    }.getOrDefault(emptyList())

    /** 网易云歌单的兜底通道（sy 体系，可能被上游截断到 20 首）。 */
    private suspend fun songsFromSy(playlistId: String, platform: OnlinePlatform): List<Song> {
        val body = request(p("p18"), mapOf("id" to playlistId)) ?: return emptyList()
        return runCatching {
            val array = JSONObject(body).optJSONArray("items") ?: JSONArray()
            (0 until array.length()).mapNotNull { index ->
                parseSong(array.optJSONObject(index), platform)
            }
        }.getOrDefault(emptyList())
    }

    /**
     * 听歌识曲：把 8kHz/16bit/单声道 PCM 原样 POST 给 `qq/api/recognize?pcm=1`。
     * 上游 3 秒起可识别，8~12 秒最稳；识别不到时后端返回 `hit=false` / `msg="not_match"`。
     */
    suspend fun recognize(pcm: ByteArray): Song? = withContext(Dispatchers.IO) {
        val body = post(p("p19"), mapOf("pcm" to "1"), pcm) ?: return@withContext null
        runCatching {
            val data = JSONObject(body).optJSONObject("data") ?: return@withContext null
            if (!data.optBoolean("hit")) return@withContext null
            val song = data.optJSONArray("songs")?.optJSONObject(0) ?: return@withContext null
            val mid = song.optString("songmid")
            if (mid.isBlank()) return@withContext null
            val albumMid = song.optString("albummid")
            val cover = if (albumMid.isBlank()) {
                ""
            } else {
                // 识曲页只显示这一张图，直接取平台最高档（QQ 1200x1200，见 CoverUrls 注释）
                CoverUrls.best("https://y.qq.com/music/photo_new/T002R300x300M000$albumMid.jpg").orEmpty()
            }
            Song(
                id = mid,
                title = song.optString("songname").ifBlank { "未命名歌曲" },
                artist = song.optString("singername").ifBlank { "未知艺术家" },
                album = song.optString("albumname"),
                durationMs = song.optLong("playtime", 0L) * 1000L,
                uri = android.net.Uri.EMPTY,
                albumArtUri = httpUrl(cover)?.let { android.net.Uri.parse(it) },
                source = SongSource.ONLINE,
                platform = OnlinePlatform.QQ,
                coverUrl = httpUrl(cover),
            )
        }.getOrNull()
    }

    /**
     * 解析视频分享链接（抖音/快手/B站）。
     *
     * 只拿「标题 + 音轨直链」这类信息，转码在端上做（服务端没有 ffmpeg）。
     * 失败时把服务端那句人话 message 带回来——"抖音风控重试失败"和"不支持这个平台"
     * 对用户是完全不同的事，不能都显示成"解析失败"。
     */
    suspend fun parseClip(link: String): ClipParse = withContext(Dispatchers.IO) {
        val body = request(p("p25"), mapOf("action" to "parse", "url" to link))
            ?: return@withContext ClipParse(null, "连不上服务器，检查网络后再试")
        runCatching {
            val root = JSONObject(body)
            if (root.optInt("code", -1) != 0) {
                return@withContext ClipParse(null, root.optString("msg").ifBlank { "解析失败" })
            }
            val data = root.optJSONObject("data")
            val clip = data?.optJSONObject("clip")
            if (data == null || clip == null) {
                return@withContext ClipParse(null, "服务器没有返回解析结果")
            }
            ClipParse(
                VideoClip(
                    platform = clip.optString("platform"),
                    title = clip.optString("title"),
                    author = clip.optString("author"),
                    coverUrl = httpUrl(clip.optString("cover")),
                    durationSec = clip.optDouble("duration", 0.0),
                    musicTitle = clip.optString("musicTitle"),
                    musicAuthor = clip.optString("musicAuthor"),
                    audioUrl = data.optString("audioUrl"),
                    audioKind = data.optString("audioKind"),
                    referer = data.optString("referer"),
                    fallbackUrl = data.optString("fallbackUrl"),
                    fallbackKind = data.optString("fallbackKind"),
                    fallbackReferer = data.optString("fallbackReferer"),
                ),
                null,
            )
        }.getOrElse { ClipParse(null, "解析结果读不出来：" + (it.message ?: "格式异常")) }
    }

    suspend fun health(platform: OnlinePlatform): Boolean = withContext(Dispatchers.IO) {
        val body = when (platform) {
            OnlinePlatform.QQ -> request(p("p20"), emptyMap())
            OnlinePlatform.NETEASE -> request(p("p21"), emptyMap())
        } ?: return@withContext false
        runCatching {
            JSONObject(body).optJSONObject("data")?.optBoolean("ok") ?: false
        }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- 解析



    /**
     * 后端部分封面仍给 http://（QQ 歌单封面常见）。targetSdk 28+ 默认禁止明文流量，
     * 这里统一升级到 https，避免封面加载被系统拦截。
     */

    /**
     * QQ 返回的歌词是逐字打轴（一行内多个 [mm:ss.xx] 标签），
     * 直接交给 LrcParser 会把整句拆成单字。这里把一行压成「句首时间 + 整句文本」。
     */

    // ---------------------------------------------------------------- 解析托管在 MusicParsing.kt

    private fun parseSong(obj: JSONObject?, platform: OnlinePlatform): Song? = MusicParsing.song(obj, platform)
    private fun parsePlaylist(obj: JSONObject?, platform: OnlinePlatform): OnlinePlaylist? = MusicParsing.playlist(obj, platform)
    private fun parseSimilarSong(obj: JSONObject?, platform: OnlinePlatform): Song? = MusicParsing.similarSong(obj, platform)
    private fun parseQqDaily(body: String): List<Song> = MusicParsing.qqDaily(body)
    private fun qqFacts(data: JSONObject, song: Song): SongFacts = MusicParsing.qqFacts(data, song)
    private fun neteaseFacts(data: JSONObject, song: Song): SongFacts = MusicParsing.neteaseFacts(data, song)
    private fun httpUrl(value: String): String? = MusicParsing.url(value)
    private fun normalizeLrc(raw: String): String = MusicParsing.lrc(raw)

    // ---------------------------------------------------------------- HTTP

    /**
     * 所有业务请求都从这里出去：先取签名头（必要时自动激活），401 时清票据重签一次。
     * 其余失败按原逻辑返回 null，调用方各自降级。
     *
     * 同时在飞的同一条 `path?query` 只会有一条：后到的调用等它的结果（单飞）。
     * 首页、歌单、预取、封面预热经常在同一刻要同一份数据，去重之后既少打接口，
     * 也不会因为上游限流把几条都拖死。
     */
    private suspend fun request(
        path: String,
        params: Map<String, String>,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String? {
        val query = buildQuery(params)
        val key = "$path?$query"
        inflight[key]?.let { running ->
            runCatching { running.await() }.getOrNull()?.let { return it }
        }
        val job = apiScope.async { execute("GET", path, query, null, readTimeoutMs) }
        inflight[key] = job
        return try {
            job.await()
        } catch (e: Exception) {
            null
        } finally {
            inflight.remove(key, job)
        }
    }

    private suspend fun post(path: String, params: Map<String, String>, body: ByteArray): String? =
        execute("POST", path, buildQuery(params), body)

    private fun buildQuery(params: Map<String, String>): String =
        params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { "${it.key}=${encode(it.value)}" }

    private suspend fun execute(
        method: String,
        path: String,
        query: String,
        body: ByteArray?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): String? {
        val fullUrl = baseUrl + path + if (query.isEmpty()) "" else "?$query"
        val signer = security
        repeat(2) { attempt ->
            val headers = if (signer == null) {
                emptyMap()
            } else {
                runCatching { signer.headersFor(method, path, query, body) }
                    .getOrDefault(emptyMap())
            }
            val result = try {
                send(method, fullUrl, headers, body, readTimeoutMs)
            } catch (e: Exception) {
                return null
            }
            when {
                result.first in 200..299 -> return result.second
                result.first == 401 && signer != null && attempt == 0 -> signer.invalidate()
                else -> return null
            }
        }
        return null
    }

    private fun send(
        method: String,
        fullUrl: String,
        headers: Map<String, String>,
        body: ByteArray?,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): Pair<Int, String?> {
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Cache-Control", "no-store")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (method != "GET") {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                val payload = body ?: ByteArray(0)
                connection.setFixedLengthStreamingMode(payload.size)
                connection.outputStream.use { it.write(payload) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }
            return code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        /**
         * 部署根地址。不再是字面量：从这里出去的值由 [com.am.music.data.security.Secrets]
         * 在运行时解出来，dex 里搜不到域名。
         */
        val BASE_URL: String
            get() = com.am.music.data.security.Secrets.baseUrl

        /** 路径短 ID → 真实路径（表在 Secrets 里加密，见那边的说明）。 */
        private fun p(id: String): String = com.am.music.data.security.Secrets.path(id)
        private const val CONNECT_TIMEOUT_MS = 12_000
        private const val READ_TIMEOUT_MS = 20_000

        /** 歌单全量取回（几百首要服务端逐首拉）单独给的长超时。 */
        private const val PLAYLIST_TIMEOUT_MS = 90_000
        private const val USER_AGENT = "AMMusic/0.6.41 (Android)"

        // 正则已随解析函数迁到 MusicParsing.kt

    }
}
