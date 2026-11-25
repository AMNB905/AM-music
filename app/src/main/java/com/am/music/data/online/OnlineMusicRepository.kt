package com.am.music.data.online

import android.content.Context
import android.net.Uri
import com.am.music.data.audio.ClipAudio
import com.am.music.data.audio.ClipScanCache
import com.am.music.data.audio.MultiSongScanner
import com.am.music.data.cache.FeedCache
import com.am.music.data.cover.CoverUrls
import com.am.music.data.download.DownloadsRepository
import com.am.music.data.model.ClipHit
import com.am.music.data.model.ClipRecognize
import com.am.music.data.model.ClipStage
import com.am.music.data.model.OnlinePlatform
import com.am.music.data.model.PlayableVariant
import com.am.music.data.model.RankingBoards
import com.am.music.data.model.Song
import com.am.music.data.model.SongFacts
import com.am.music.data.model.VideoClip
import com.am.music.data.settings.SettingsRepository
import com.am.music.playback.MediaCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface OnlineMusicRepository {
    suspend fun health(platform: OnlinePlatform): Boolean

    suspend fun hotKeywords(platform: OnlinePlatform, forceRefresh: Boolean = false): List<String>

    suspend fun search(keyword: String, platform: OnlinePlatform): List<Song>

    /**
     * 发现页一次取齐：热词 + 推荐歌单 + 每日推荐 + 连通性，四路并行。
     *
     * 聚合的意义在「切音源」：以前切一次要触发四个独立请求、四个骨架，
     * 现在一份 [DiscoverFeed] 就能整体记住 / 整体落盘。
     */
    suspend fun discover(
        platform: OnlinePlatform,
        dailyLimit: Int = 30,
        forceRefresh: Boolean = false,
    ): DiscoverFeed

    /** 发现页磁盘快照（不发网络）：冷启动先把上一份画出来。 */
    suspend fun discoverSnapshot(platform: OnlinePlatform, dailyLimit: Int = 30): DiscoverFeed?

    suspend fun playlists(platform: OnlinePlatform, limit: Int = 12, forceRefresh: Boolean = false): List<OnlinePlaylist>

    suspend fun playlistSongs(
        playlistId: String,
        platform: OnlinePlatform,
        forceRefresh: Boolean = false,
    ): List<Song>

    /** 歌单详情磁盘快照：刚刚打开过的歌单，再打开不必等接口。 */
    suspend fun playlistSongsSnapshot(playlistId: String, platform: OnlinePlatform): List<Song>?

    /**
     * 导入歌单用：一次拿回歌单名与全部曲目，**不走缓存**（用户要的是此刻的样子）。
     * null = 请求失败；曲目为空 = 私密 / 全下架 / 被限流。
     */
    suspend fun playlistBundle(playlistId: String, platform: OnlinePlatform): PlaylistBundle?

    /**
     * 榜单歌曲（榜单 id 见 [com.am.music.data.model.RankingBoards]，QQ 走 topid）。
     * 走「每进程只拉一次 + 磁盘快照」口径，[forceRefresh] 供手动刷新。
     */
    suspend fun toplistSongs(
        boardId: String,
        limit: Int = 50,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
        forceRefresh: Boolean = false,
    ): List<Song>

    /** 每日推荐（网易云 personalized/newsong / QQ 每日30首）。 */
    suspend fun dailySongs(
        limit: Int = 30,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
        forceRefresh: Boolean = false,
    ): List<Song>

    /** 榜单 / 每日推荐的磁盘快照：冷启动先出内容，再后台刷新。 */
    suspend fun toplistSnapshot(
        boardId: String,
        limit: Int = 50,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
    ): List<Song>?

    suspend fun dailySongsSnapshot(
        limit: Int = 30,
        platform: OnlinePlatform = OnlinePlatform.NETEASE,
    ): List<Song>?

    /** 启动预热：把首页型数据拉一次并落盘，之后各页直接用缓存。 */
    suspend fun prewarm()

    /**
     * 解析播放直链。quality 传 null 时使用设置里的音质档位；
     * 同一首歌 + 同一档位只解析一次，结果缓存在内存里。
     */
    suspend fun streamUrl(song: Song, quality: String? = null): String?

    /**
     * 强制重新签一条直链，绕开内存里的旧地址。
     *
     * 播放中断时用它：直链是「一次一签」的，放久了自己就失效（403/断流），
     * 拿旧地址重试必然再失败一次，所以这里必须先作废旧值再取。
     */
    suspend fun freshStreamUrl(song: Song, quality: String? = null): String?

    /** 拉取线上歌词（LRC 文本，已归一化行级时间轴）。 */
    suspend fun lyrics(song: Song): String?

    /** 批量解析播放直链并发回带 uri 的歌曲，供播放器直接入队。 */
    suspend fun playable(songs: List<Song>): List<Song>

    /**
     * 只解析一首歌（点击播放时先走这条，立刻出声，其余曲目后台补）。
     * 解析失败返回 null。
     */
    suspend fun resolve(song: Song): Song?

    /** 相似歌曲（新接口）：拿不到就返回空，由界面决定不显示入口。 */
    suspend fun similarSongs(song: Song, forceRefresh: Boolean = false): List<Song>

    /** 歌曲资料（百科）：上游没有这条就返回 null。 */
    suspend fun songFacts(song: Song, forceRefresh: Boolean = false): SongFacts?

    /** 听歌识曲：上传 8kHz/16bit/单声道 PCM，命中返回识别到的歌曲。 */
    suspend fun recognize(pcm: ByteArray): Song?

    /**
     * 粘贴视频分享链接识别（抖音/快手/B站）：
     * 服务端解析出音轨直链 → 端上下载并解码成 PCM → 走同一条识别链。
     *
     * [onStage] 会在每个阶段回调一次（解析/下载/解码/上传），界面据此给等待提示；
     * [onProgress] 给更细的进度（"第 3/8 段 · 第 58s"），只有长视频分段识别时才有内容；
     * [onHits] 每听出一首就回调一次累积列表，界面可以边扫边显示。
     * [rangeSec] 非空表示只扫这一段（如 60..120），空表示整条。
     * 回调发生在调用方协程的线程上，实现方不要假设主线程。
     */
    suspend fun recognizeLink(
        link: String,
        onStage: (ClipStage) -> Unit,
        onProgress: (String) -> Unit = {},
        onHits: (List<ClipHit>) -> Unit = {},
        rangeSec: IntRange? = null,
    ): ClipRecognize

    /**
     * 识曲结果的播放兜底：原来那条放不了，就按同名同作者去网易云找一条能放的。
     *
     * 为什么必须在端上做一次「真的解析一下」：QQ 的版权状态只有直链接口知道，
     * 曲目列表里看不出来；用户点播放才知道放不了，那是最差体验。
     */
    suspend fun playableVariant(song: Song): PlayableVariant

    /** 清空直链缓存（切换音质 / 手动清理时用）。 */
    fun clearStreamCache()
}

class RemoteOnlineMusicRepository(
    private val settings: SettingsRepository,
    private val api: MusicApi = MusicApi(),
    /** 只为拿磁盘缓存的上下文；不参与请求。 */
    private val context: Context? = null,
    /** 用户主动下载的曲目，优先级高于播放缓存与直链。 */
    private val downloads: DownloadsRepository? = null,
) : OnlineMusicRepository {

    /** 缓存键带上音质，切档后不会命中旧直链。 */
    private val streamCache = ConcurrentHashMap<String, String>()

    /** 链接识别结果缓存；没有 context 时为空（例如单测）。 */
    private val scanCache: ClipScanCache? by lazy {
        context?.let { ClipScanCache(File(it.filesDir, "recognize_cache")) }
    }

    override suspend fun health(platform: OnlinePlatform): Boolean = api.health(platform)

    override suspend fun hotKeywords(platform: OnlinePlatform, forceRefresh: Boolean): List<String> =
        api.hotKeywords(platform, forceRefresh)

    override suspend fun search(keyword: String, platform: OnlinePlatform): List<Song> =
        api.search(keyword, platform)

    override suspend fun discover(
        platform: OnlinePlatform,
        dailyLimit: Int,
        forceRefresh: Boolean,
    ): DiscoverFeed = coroutineScope {
        // 四路并行：任何一路失败都只是那块为空，不让整页变成错误页
        val hot = async { runCatching { hotKeywords(platform, forceRefresh) }.getOrDefault(emptyList()) }
        val lists = async {
            runCatching { playlists(platform, PLAYLISTS_LIMIT, forceRefresh) }.getOrDefault(emptyList())
        }
        val daily = async {
            runCatching { dailySongs(dailyLimit, platform, forceRefresh) }.getOrDefault(emptyList())
        }
        val reachable = async { runCatching { health(platform) }.getOrDefault(false) }
        DiscoverFeed(hot.await(), lists.await(), daily.await(), reachable.await())
    }

    override suspend fun discoverSnapshot(platform: OnlinePlatform, dailyLimit: Int): DiscoverFeed? =
        coroutineScope {
            val hot = async { runCatching { api.hotKeywordsSnapshot(platform) }.getOrNull() }
            val lists = async { runCatching { api.playlistsSnapshot(PLAYLISTS_LIMIT) }.getOrNull() }
            val daily = async { runCatching { api.dailySongsSnapshot(dailyLimit, platform) }.getOrNull() }
            DiscoverFeed(
                hotKeywords = hot.await().orEmpty(),
                playlists = lists.await().orEmpty(),
                dailySongs = daily.await().orEmpty(),
            ).takeIf { !it.isEmpty }
        }

    override suspend fun playlists(
        platform: OnlinePlatform,
        limit: Int,
        forceRefresh: Boolean,
    ): List<OnlinePlaylist> = api.playlists(platform, limit, forceRefresh)

    override suspend fun playlistSongs(
        playlistId: String,
        platform: OnlinePlatform,
        forceRefresh: Boolean,
    ): List<Song> = api.playlistSongs(playlistId, platform, forceRefresh)

    override suspend fun playlistBundle(playlistId: String, platform: OnlinePlatform): PlaylistBundle? =
        api.playlistBundle(playlistId, platform)

    override suspend fun playlistSongsSnapshot(playlistId: String, platform: OnlinePlatform): List<Song>? =
        api.playlistSongsSnapshot(playlistId, platform)

    override suspend fun toplistSongs(
        boardId: String,
        limit: Int,
        platform: OnlinePlatform,
        forceRefresh: Boolean,
    ): List<Song> = api.toplistSongs(boardId, limit, platform, forceRefresh)

    override suspend fun toplistSnapshot(
        boardId: String,
        limit: Int,
        platform: OnlinePlatform,
    ): List<Song>? = api.toplistSongsSnapshot(boardId, limit, platform)

    override suspend fun dailySongsSnapshot(
        limit: Int,
        platform: OnlinePlatform,
    ): List<Song>? = api.dailySongsSnapshot(limit, platform)

    override suspend fun dailySongs(
        limit: Int,
        platform: OnlinePlatform,
        forceRefresh: Boolean,
    ): List<Song> = api.dailySongs(limit, platform, forceRefresh)

    /**
     * 启动预热：把「一眼就要看到」的数据各拉一次（每日推荐两平台 + 两平台的首个榜单）。
     *
     * 放在 IO 后台跑，失败静默——下次进页面还会按「本进程首次访问」再试一次，
     * 不会因为一次网络抖动就把首页变空。
     */
    override suspend fun prewarm() = coroutineScope {
        val jobs = listOf(
            async { runCatching { dailySongs(platform = OnlinePlatform.NETEASE) } },
            async { runCatching { dailySongs(platform = OnlinePlatform.QQ) } },
            async {
                runCatching {
                    toplistSongs(
                        boardId = RankingBoards.NETEASE.first().id,
                        platform = OnlinePlatform.NETEASE,
                    )
                }
            },
            async {
                runCatching {
                    toplistSongs(
                        boardId = RankingBoards.QQ.first().id,
                        platform = OnlinePlatform.QQ,
                    )
                }
            },
        )
        jobs.awaitAll()
        Unit
    }

    override suspend fun streamUrl(song: Song, quality: String?): String? {
        song.streamUrl?.takeIf { it.startsWith("http") }?.let { return it }
        val level = quality ?: settings.qualityValue
        val cacheKey = "$level@${song.key}"
        streamCache[cacheKey]?.let { return it }
        val url = api.streamUrl(song, level) ?: return null
        streamCache[cacheKey] = url
        return url
    }

    /**
     * 本地已有整首缓存 → 直接给一个「缓存播放」的 Song：uri 是占位地址，
     * 播放器按 customCacheKey 从磁盘读，不碰网络，也不等解析直链。
     * 这就是「有本地缓存优先走本地缓存」的入口。
     */
    private fun cachedPlayback(song: Song): Song? {
        val preferred = settings.qualityValue
        val quality = MediaCache.cachedQuality(song.key, preferred, context) ?: return null
        return song.copy(
            uri = MediaCache.placeholderUri(song.key, quality),
            streamUrl = null,
            cachedQuality = quality,
        )
    }

    override suspend fun freshStreamUrl(song: Song, quality: String?): String? {
        val level = quality ?: settings.qualityValue
        val key = "$level@${song.key}"
        streamCache.remove(key)
        val url = api.streamUrl(song, level) ?: return null
        streamCache[key] = url
        return url
    }

    override suspend fun lyrics(song: Song): String? = api.lyrics(song)

    override suspend fun playable(songs: List<Song>): List<Song> = coroutineScope {
        val gate = Semaphore(PLAYLIST_RESOLVE_PARALLELISM)
        songs.map { song ->
            async {
                // 优先级：用户下载的文件 > 整首播放缓存 > 网络直链
                downloads?.offlineCopy(song)?.let { return@async it }
                cachedPlayback(song)?.let { return@async it }
                gate.withPermit {
                    val url = streamUrl(song)
                    if (url != null) {
                        song.copy(streamUrl = url, uri = Uri.parse(url))
                    } else {
                        cachedPlayback(song) ?: song
                    }
                }
            }
        }.awaitAll()
    }

    override suspend fun resolve(song: Song): Song? {
        // 下载过的一律走本地文件：离线首选，也免得白解析一条迟早过期的直链
        downloads?.offlineCopy(song)?.let { return it }
        cachedPlayback(song)?.let { return it }
        val url = streamUrl(song) ?: return cachedPlayback(song)
        return song.copy(streamUrl = url, uri = Uri.parse(url))
    }

    override suspend fun similarSongs(song: Song, forceRefresh: Boolean): List<Song> =
        api.similarSongs(song, forceRefresh)

    override suspend fun songFacts(song: Song, forceRefresh: Boolean): SongFacts? =
        api.songFacts(song, forceRefresh)

    override suspend fun recognize(pcm: ByteArray): Song? = api.recognize(pcm)

    /**
     * 链接识别的四步：解析 → 下载 → 解码 → 上传识别。
     *
     * 每一步失败都给不同的说法，因为用户能采取的动作不一样：
     * 解析失败要「换条链接/稍后再试」，下载失败要「重新粘一次（地址会过期）」，
     * 解码失败要「换一条视频」，识别未命中则说明歌不在曲库里。
     *
     * 抖音/快手有个额外麻烦：服务端给的 `music/bgm` 只是**配乐原声**，不等于视频里
     * 真正在响的声音（作者可以换音乐、也可以纯混音 + 现场收音）。所以识别不出来时
     * 必须接着试混流视频的音轨（`mix/mp4`），而不是拿 BGM 的结论直接判死。
     */
    override suspend fun recognizeLink(
        link: String,
        onStage: (ClipStage) -> Unit,
        onProgress: (String) -> Unit,
        onHits: (List<ClipHit>) -> Unit,
        rangeSec: IntRange?,
    ): ClipRecognize {
        onStage(ClipStage.PARSING)
        val parsed = api.parseClip(link)
        val clip = parsed.clip ?: return ClipRecognize(null, null, parsed.message ?: "解析失败")
        val sources = sourcesOf(clip)
        if (sources.isEmpty()) {
            return ClipRecognize(null, clip, "这条没音轨（可能是图集）")
        }
        val ctx = context ?: return ClipRecognize(null, clip, "缺少应用上下文，无法落临时文件")
        val dir = File(ctx.cacheDir, "clips")

        // 整条扫描命中过就留档：同一条链接第二次进来直接给结果，不下载也不上传。
        // 留档里可能是旧版本存下的 300x300 封面，取出来时统一抬到最高档。
        if (rangeSec == null) {
            scanCache?.read(link)?.let { cached ->
                return cached.copy(
                    song = cached.song?.withBestCover(),
                    hits = cached.hits.map { it.copy(song = it.song.withBestCover()) },
                )
            }
        }

        // 长视频：一条里可能连着好几首，交给通用扫描器（能量筛段 + 采样识别）。
        // 这里把「视频音轨」排到前面：几分钟的视频里，十几秒的 BGM 原声根本代表不了
        // 视频里在响的声音，先扫它只会白白多花几次识别请求。
        if (clip.durationSec >= MULTI_MIN_SECONDS) {
            val result = recognizeMulti(
                clip,
                sources.sortedBy { if (it.bgmOnly) 1 else 0 },
                dir, onStage, onProgress, onHits, rangeSec,
            )
            if (rangeSec == null && result.hits.isNotEmpty()) {
                scanCache?.write(link, result.clip, result.hits)
            }
            return result
        }

        /*
         * 短视频：按来源逐条升级，每条内部再分「前 4MB → 整条」两档。
         *
         * 1. BGM 原声前 4MB（最快：抖音的 BGM mp3 通常几 MB）
         * 2. BGM 原声整条（moov 在尾部时才需要）
         * 3. 混流视频音轨整条（BGM 解得出但听不出来，就是"那不是视频里的声音"）
         *
         * 关键在于「识别返回 null 也要继续下一条」——旧代码一解出 PCM 就 break，
         * BGM 明明可解、只是不是真歌，于是永远走不到视频音轨。
         */
        var decodedAny = false
        for ((index, source) in sources.withIndex()) {
            if (index > 0) onProgress("换用${source.label}再试…")
            val srcClip = clip.copy(audioUrl = source.url, referer = source.referer)
            val capMb = capFor(source) / (1024 * 1024)

            onStage(ClipStage.DOWNLOADING)
            onProgress("正在下载${source.label}…")
            val head = ClipAudio.download(dir, srcClip, true)
            onStage(ClipStage.DECODING)
            val headPcm = head?.let { ClipAudio.decodeToPcm(it) }
            head?.delete()
            if (headPcm != null && headPcm.size >= ClipAudio.MIN_PCM_BYTES) {
                decodedAny = true
                onStage(ClipStage.UPLOADING)
                val song = recognize(headPcm)
                if (song != null) {
                    val hits = listOf(ClipHit(song, 0))
                    onHits(hits)
                    // 短视频也留档：第二次点同一条直接出结果
                    if (rangeSec == null) scanCache?.write(link, clip, hits)
                    return ClipRecognize(song, clip, null, hits)
                }
                // 解得出、听不出来 = 这不是视频里在响的那段声音，换下一条来源
                onProgress("${source.label}没对上，接着试下一条…")
                continue
            }

            // 前 4MB 解不开（MP4 的 moov 在尾部时就是这样）：整条再来一次
            onStage(ClipStage.DOWNLOADING)
            onProgress("正在下载${source.label}（最多 ${capMb}MB）…")
            val whole = ClipAudio.download(dir, srcClip, false, maxBytes = capFor(source))
            onStage(ClipStage.DECODING)
            val pcm = whole?.let { ClipAudio.decodeToPcm(it) }
            whole?.delete()
            if (pcm == null || pcm.size < ClipAudio.MIN_PCM_BYTES) continue
            decodedAny = true
            onStage(ClipStage.UPLOADING)
            val song = recognize(pcm) ?: continue
            val hits = listOf(ClipHit(song, 0))
            onHits(hits)
            if (rangeSec == null) scanCache?.write(link, clip, hits)
            return ClipRecognize(song, clip, null, hits)
        }

        // 30~45 秒的也可能是「两首拼在一起」：整条都没对上时，只在视频音轨上分段再捞一遍
        if (decodedAny && clip.durationSec >= SHORT_SCAN_MIN_SECONDS) {
            val tail = sources.filter { !it.bgmOnly }
            if (tail.isNotEmpty()) {
                val result = recognizeMulti(
                    clip, tail, dir, onStage, onProgress, onHits, rangeSec,
                    maxCalls = SHORT_SCAN_CALLS,
                )
                if (result.hits.isNotEmpty()) {
                    if (rangeSec == null) scanCache?.write(link, result.clip, result.hits)
                    return result
                }
            }
        }

        return ClipRecognize(
            null,
            clip,
            if (decodedAny) {
                "视频有声音，曲库没对上"
            } else {
                "音轨解不开（可能加密），换一条"
            },
        )
    }

    /**
     * 多曲识别。
     *
     * 分工：下载与「能不能解」在这里，**怎么切窗口**交给 [MultiSongScanner]——
     * 先跑一遍能量地图筛掉没声的段，预算内逐段识别，超预算再按均匀间隔抽；2 路并发。
     *
     * 来源按「先便宜后完整」排：BGM 原声（通常十几秒，先扫一遍，命中就直接收）→
     * 混流视频音轨（整条，可能几十 MB，**只有 BGM 没对上才下**）。
     * 抖音这类「作者换了音乐」的视频，唯一能听到真歌的就是混流那条。
     *
     * [rangeSec] 非空时只扫这一段（用户填「只听 1:00-2:00」）。
     * 每听出一首就通过 [onHits] 回一次，界面可以边扫边把结果排出来。
     */
    private suspend fun recognizeMulti(
        clip: VideoClip,
        sources: List<ClipSource>,
        dir: File,
        onStage: (ClipStage) -> Unit,
        onProgress: (String) -> Unit,
        onHits: (List<ClipHit>) -> Unit,
        rangeSec: IntRange? = null,
        maxCalls: Int = MULTI_MAX_CALLS,
    ): ClipRecognize {
        var decoded = false
        for ((index, source) in sources.withIndex()) {
            if (index > 0) onProgress("换用${source.label}再试…")
            val cap = capFor(source)
            onStage(ClipStage.DOWNLOADING)
            onProgress("正在下载${source.label}（最多 ${cap / (1024 * 1024)}MB）…")
            val file = ClipAudio.download(
                dir,
                clip.copy(audioUrl = source.url, referer = source.referer),
                partial = false,
                maxBytes = cap,
            ) ?: continue
            onStage(ClipStage.DECODING)
            onProgress("正在试解第 ${MULTI_START_SECONDS} 秒…")
            val probe = ClipAudio.decodeToPcm(file, MULTI_START_SECONDS, WINDOW_SECONDS)
            if (probe == null || probe.size < ClipAudio.MIN_PCM_BYTES) {
                file.delete()
                continue
            }
            decoded = true
            try {
                // 下载被 maxBytes 截断时，以文件自己的时长为准，免得扫到没数据的窗口
                val durationSec = ClipAudio.durationMs(file)?.let { (it / 1000L).toInt() }
                    ?: clip.durationSec.toInt()
                val live = mutableListOf<ClipHit>()
                val hits = MultiSongScanner(
                    windowSeconds = WINDOW_SECONDS,
                    maxCalls = if (source.bgmOnly) BGM_MAX_CALLS else maxCalls,
                ).scan(
                    durationSec = durationSec,
                    decode = { start, seconds -> ClipAudio.decodeToPcm(file, start, seconds) },
                    recognize = { pcm ->
                        onStage(ClipStage.UPLOADING)
                        recognize(pcm)
                    },
                    fromSec = rangeSec?.first ?: 0,
                    toSec = rangeSec?.last ?: 0,
                    onProgress = onProgress,
                    onHit = { hit ->
                        live += hit
                        onHits(live.toList())
                    },
                )
                if (hits.isNotEmpty()) {
                    return ClipRecognize(hits.first().song, clip, null, hits)
                }
            } finally {
                file.delete()
            }
        }
        return ClipRecognize(
            null,
            clip,
            if (decoded) {
                "视频有声音，曲库没对上"
            } else {
                "音轨解不开（可能加密），换一条"
            },
            emptyList(),
        )
    }

    /**
     * 一条解析结果里可用的声音来源，按「先便宜后完整」排好。
     *
     * - `music/bgm`：服务端只给了 BGM 原声，**不等于**视频里真正在响的声音；
     * - `mix/mp4`：混流 MP4，音轨就是视频里那段声音（这次降级的主角）；
     * - `audio/...`：B 站那种独立音轨。
     */
    private fun sourcesOf(clip: VideoClip): List<ClipSource> {
        val list = mutableListOf<ClipSource>()
        fun add(url: String, referer: String, kind: String, suffix: String = "") {
            if (url.isBlank() || !url.startsWith("http")) return
            if (list.any { it.url == url }) return
            list += ClipSource(url, referer, describeKind(kind) + suffix, kind.startsWith("music/"))
        }
        fun addVideo(url: String, referer: String, kind: String) {
            // 先挂一条「720p 改写版」：抖音原画（ratio=default）是 91MB 的普通 MP4，
            // moov 在文件尾部，只取前几 MB 根本解不开；720p 那条 7MB 左右且 moov 在前，
            // 一次就能整条下完，音轨一样是 44.1kHz 的 AAC。
            slimVideoUrl(url)?.let { add(it, referer, kind, "（720p）") }
            add(url, referer, kind)
        }
        // BGM 是 mp3、B 站是 m4a，改写函数对它们返回 null，只有抖音混流会被挂上 720p 版
        addVideo(clip.audioUrl, clip.referer, clip.audioKind)
        addVideo(clip.fallbackUrl, clip.fallbackReferer, clip.fallbackKind)
        return list
    }

    /**
     * 抖音播放直链的「小体积同源」改写：`ratio=default`（原画）→ `ratio=720p`。
     *
     * 实测同一支 89 秒视频：原画 91.4MB、结构是 ftyp→mdat→moov（moov 在尾部），
     * 截断下载必然解不开；720p 只有 7.7MB、结构是 ftyp→moov→mdat（moov 在前），
     * 音轨时长 89.42 秒、44.1kHz AAC，与视频本体完全一致。
     *
     * 只在自有 play 直链上改写；其它平台的地址原样返回 null（不改）。
     */
    private fun slimVideoUrl(url: String): String? {
        if (!url.contains("aweme/v1/play")) return null
        val target = "ratio=720p"
        if (url.contains("ratio=720p") || url.contains("ratio=540p") || url.contains("ratio=480p")) return null
        return if (url.contains("ratio=")) {
            url.replace(Regex("ratio=[^&]*"), target)
        } else {
            (if (url.contains("?")) "$url&" else "$url?") + target
        }
    }

    private fun describeKind(kind: String): String = when {
        kind.startsWith("music/") -> "BGM 原声"
        kind.startsWith("mix/") -> "视频音轨"
        kind.startsWith("audio/") -> "独立音轨"
        kind.isBlank() -> "音轨"
        else -> kind
    }

    /** 下载上限：BGM 原声只有十几 MB，混流是整条视频，给大得多的口径。 */
    private fun capFor(source: ClipSource): Long =
        if (source.bgmOnly) BGM_MAX_BYTES else MIX_MAX_BYTES

    /** 一条能喂给识别的音源；[bgmOnly] 表示它只是配乐，别拿它的结论判死。 */
    private data class ClipSource(
        val url: String,
        val referer: String,
        val label: String,
        val bgmOnly: Boolean,
    )


    /**
     * 播放兜底：QQ 没有可播版本时，按「同名 + 同作者」去网易云找一条真能放出来的。
     *
     * 判定顺序：
     * 1. 原曲目能解析出直链 → 直接用；
     * 2. 平台不是 QQ（本地/网易）→ 不换源，如实说没有可播地址；
     * 3. 网易云搜索结果里标题规范化后完全相同、作者有交集的前 3 条，逐条试着解析，
     *    第一条真能解析的直接换过去；
     * 4. 都不行 → 返回空 song，界面保留识别到的信息但明说两边都放不了。
     */
    override suspend fun playableVariant(song: Song): PlayableVariant {
        resolve(song)?.let { return PlayableVariant(it.withBestCover(), false, null) }
        if (song.platform != OnlinePlatform.QQ) {
            return PlayableVariant(null, false, "《${song.title}》暂时没播放地址")
        }
        val keyword = listOf(song.title, song.artist)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val candidates = runCatching { search(keyword, OnlinePlatform.NETEASE) }
            .getOrDefault(emptyList())
        val wanted = normalizeName(song.title)
        val matches = candidates.filter { candidate ->
            normalizeName(candidate.title) == wanted && artistRelated(candidate.artist, song.artist)
        }.take(3)
        for (candidate in matches) {
            resolve(candidate)?.let { resolved ->
                return PlayableVariant(
                    resolved.withBestCover(),
                    true,
                    "QQ 没有可播版，已换网易云同款",
                )
            }
        }
        return PlayableVariant(
            null,
            false,
            "识别到《${song.title}》· ${song.artist}，两个源都不能播",
        )
    }

    /**
     * 识别结果（含换源后的网易版本）的封面一律取平台最高档：
     * QQ 抬到 1200x1200，网易去掉 `?param=300y300` 这类降采样参数拿原图。
     * 规则与实测数据见 [CoverUrls]。
     */
    private fun Song.withBestCover(): Song {
        val best = CoverUrls.best(coverUrl) ?: return this
        if (best == coverUrl) return this
        return copy(coverUrl = best, albumArtUri = Uri.parse(best))
    }

    /** 只留汉字/字母/数字，去掉括号、标点、空格；大小写不敏感。 */
    private fun normalizeName(raw: String): String {
        val builder = StringBuilder(raw.length)
        raw.lowercase().forEach { ch ->
            if (ch.isLetterOrDigit()) builder.append(ch)
        }
        return builder.toString()
    }

    /** 作者是否「同一拨人」：一方包含另一方，或按 / 、 & , 切开后有交集。 */
    private fun artistRelated(left: String, right: String): Boolean {
        val a = normalizeName(left)
        val b = normalizeName(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a.contains(b) || b.contains(a)) return true
        val tokens = left.split('/', '、', '&', ',', '，', ';', '；')
            .map { normalizeName(it) }
            .filter { it.isNotBlank() }
        val other = right.split('/', '、', '&', ',', '，', ';', '；')
            .map { normalizeName(it) }
            .filter { it.isNotBlank() }
        return tokens.any { t -> other.any { o -> t == o || t.contains(o) || o.contains(t) } }
    }

    override fun clearStreamCache() {
        streamCache.clear()
    }

    private companion object {
        /** 分段识别：窗口长度（秒）。 */
        const val WINDOW_SECONDS = 12

        /** 试解用的起点：跳过开头的片头/口播，8 秒处通常已经在音乐里。 */
        const val MULTI_START_SECONDS = 8

        /** 一次扫描最多上传多少次：4 分半通常 5~8 次，10 分钟的合集也压在 10 次以内。 */
        const val MULTI_MAX_CALLS = 10

        /** 长于这个时长才走分段（短于 45 秒的通常就是一首歌）。 */
        const val MULTI_MIN_SECONDS = 45.0

        /**
         * 混流视频音轨（`mix/mp4`）的下载上限：48MB。
         *
         * 这里下的是**视频本体**：4 分半的抖音混流 MP4 常在 30~50MB，只取 12MB 的话
         * 后半段根本没有数据可解。截断了也不会认错——扫描器按文件自己的时长收尾。
         */
        const val MIX_MAX_BYTES = 48L * 1024 * 1024

        /** BGM 原声（`music/bgm`）通常只有十几 MB，单独给个小口径。 */
        const val BGM_MAX_BYTES = 12L * 1024 * 1024

        /** BGM 原声最多扫几段：它本来只对应一首歌，扫多了纯浪费请求。 */
        const val BGM_MAX_CALLS = 4

        /** 短于这个时长不算「一条里多首」，不再为它多跑一轮分段。 */
        const val SHORT_SCAN_MIN_SECONDS = 30.0

        /** 短视频兜底分段的预算：30~45 秒最多 4 段。 */
        const val SHORT_SCAN_CALLS = 4

        /**
         * 解析直链的并发数。
         *
         * 4 → 6：实测 6 路时首屏 30 首的「补队列」比 4 路快约 35%，
         * 又还没到上游开始回 429 的程度（再多就明显互相拖累）。
         */
        const val PLAYLIST_RESOLVE_PARALLELISM = 6

        /** 首页推荐歌单一次取 12 张（两列六行）。 */
        const val PLAYLISTS_LIMIT = 12
    }
}
